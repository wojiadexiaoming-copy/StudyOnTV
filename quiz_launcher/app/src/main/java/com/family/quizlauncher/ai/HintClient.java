package com.family.quizlauncher.ai;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.family.quizlauncher.Question;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agnes 提示客户端（OpenAI 兼容接口）。
 *
 * - 非流式、response_format=json_object、13 秒硬超时；
 * - 全部在单线程后台执行，同一道题只发一次请求，结果进内存缓存；
 * - 只发题干，绝不发 options / answer；
 * - Key 从加密存储读，不写日志。
 */
public final class HintClient {

    public interface Callback {
        void onHint(Hint hint, boolean fromCache);

        void onUnavailable(int reason);
    }

    public static final int REASON_OFFLINE = 0;
    public static final int REASON_AUTH = 1;
    public static final int REASON_RATE = 2;
    public static final int REASON_PARSE = 3;
    public static final int REASON_TIMEOUT = 4;
    public static final int REASON_NO_KEY = 5;
    public static final int REASON_LEAK = 6;

    private static final String ENDPOINT = "https://api.agnes-ai.cn/v1/chat/completions";
    private static final String MODEL = "agnes-3.0-flash";
    private static final long CONNECT_TIMEOUT_MS = 5000L;
    private static final long READ_TIMEOUT_MS = 90000L;
    /**
     * Agnes 免费档实测延迟 22~41 秒（不同时段波动明显）；超时太短会让在线路径
     * 必然回落本地。这里放宽到 95 秒，面板会一直显示「解析中」，可随时关闭返回答题。
     */
    private static final long HARD_TIMEOUT_MS = 95000L;

    private static final String SYSTEM_PROMPT =
            "你是\"作业教练\"，陪伴小学生写作业。你只给启发式提示，绝不给出答案。\n"
                    + "硬性规则：\n"
                    + "1. 不计算、不给出任何算式的结果；不说出任何一个选项内容；"
                    + "不出现\"答案是\"\"选X\"等表述。\n"
                    + "2. 禁止使用\"三六十八\"这类包含结果的乘法口诀原文；"
                    + "禁止\"容易算成6或9\"这类错误结果举例。\n"
                    + "3. 英语题不要直接给出应填的单词、短语或完整句子，只提示语法线索、"
                    + "固定搭配类别和检查方法。\n"
                    + "4. 只输出一个 JSON 对象，不要 Markdown、不要代码块、不要任何解释性前后缀。\n"
                    + "5. 字段固定为：hint（字符串，一句话启发，20~40 字）、"
                    + "steps（字符串数组，1~3 条，每条都不含结果）、concept（知识点）、"
                    + "pitfall（常见错误，不含数字）、need_more_info（布尔值）。\n"
                    + "6. 全部使用简体中文，语气温和、鼓励，不夹英文。\n"
                    + "7. 题目信息不足或无法判断时，need_more_info 置为 true，hint 说明缺少什么信息。";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
            new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "hint-client");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    /** 同一道题正在请求时后来者挂在这里，避免并发触发 429。 */
    private static final Map<String, List<Callback>> PENDING = new HashMap<String, List<Callback>>();

    private HintClient() {
    }

    public static void request(Context context, final Question question, Callback callback) {
        if (context == null || question == null) {
            if (callback != null) {
                callback.onUnavailable(REASON_PARSE);
            }
            return;
        }
        Hint cached = HintCache.get().get(question.text);
        if (cached != null) {
            if (callback != null) {
                final Hint hint = cached;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onHint(hint, true);
                    }
                });
            }
            return;
        }
        final Context appContext = context.getApplicationContext();
        synchronized (PENDING) {
            List<Callback> waiting = PENDING.get(question.text);
            if (waiting != null) {
                if (callback != null) {
                    waiting.add(callback);
                }
                return;
            }
            waiting = new ArrayList<Callback>();
            if (callback != null) {
                waiting.add(callback);
            }
            PENDING.put(question.text, waiting);
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                fetch(appContext, question);
            }
        });
    }

    /** 答题页渲染时预取当前题的提示，失败静默。 */
    public static void prefetch(Context context, Question question) {
        if (question == null || !ApiKeys.hasLlmKey(context)) {
            return;
        }
        request(context, question, null);
    }

    public static void cancel(Callback callback) {
        if (callback == null) {
            return;
        }
        synchronized (PENDING) {
            for (List<Callback> list : PENDING.values()) {
                list.remove(callback);
            }
        }
    }

    private static void fetch(Context context, Question question) {
        String key = ApiKeys.getLlmKey(context);
        if (TextUtils.isEmpty(key)) {
            finish(question.text, null, REASON_NO_KEY);
            return;
        }
        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicReference<HttpURLConnection> connectionRef =
                new AtomicReference<HttpURLConnection>();
        Runnable timeout = new Runnable() {
            @Override
            public void run() {
                if (!done.compareAndSet(false, true)) {
                    return;
                }
                HttpURLConnection connection = connectionRef.get();
                if (connection != null) {
                    try {
                        connection.disconnect();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
                finish(question.text, null, REASON_TIMEOUT);
            }
        };
        MAIN.postDelayed(timeout, HARD_TIMEOUT_MS);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connectionRef.set(connection);
            connection.setConnectTimeout((int) CONNECT_TIMEOUT_MS);
            connection.setReadTimeout((int) READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Accept", "application/json");

            byte[] payload = buildPayload(question).getBytes("UTF-8");
            OutputStream out = connection.getOutputStream();
            try {
                out.write(payload);
                out.flush();
            } finally {
                closeQuietly(out);
            }

            int code = connection.getResponseCode();
            if (done.get()) {
                return;
            }
            if (code != 200) {
                if (!done.compareAndSet(false, true)) {
                    return;
                }
                finish(question.text, null, reasonForHttp(code));
                return;
            }
            String body = readStream(connection.getInputStream());
            String content = extractContent(body);
            Hint parsed = parseHint(content);
            if (parsed == null || !parsed.isUsable()) {
                if (!done.compareAndSet(false, true)) {
                    return;
                }
                finish(question.text, null, REASON_PARSE);
                return;
            }
            Hint safe = HintGuard.sanitize(parsed, question);
            if (!done.compareAndSet(false, true)) {
                return;
            }
            if (safe == null) {
                // 命中答案泄露：丢弃整条提示，UI 走本地通用思路。
                finish(question.text, null, REASON_LEAK);
                return;
            }
            HintCache.get().put(question.text, safe);
            finish(question.text, safe, -1);
        } catch (java.net.SocketTimeoutException e) {
            if (done.compareAndSet(false, true)) {
                finish(question.text, null, REASON_TIMEOUT);
            }
        } catch (Throwable t) {
            if (done.compareAndSet(false, true)) {
                finish(question.text, null, REASON_OFFLINE);
            }
        } finally {
            MAIN.removeCallbacks(timeout);
            if (connection != null) {
                try {
                    connection.disconnect();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }

    private static String buildPayload(Question question) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", SYSTEM_PROMPT);
        messages.put(system);
        JSONObject user = new JSONObject();
        String subject = looksEnglish(question.text) ? "英语" : "综合";
        user.put("role", "user");
        user.put("content", "年级：小学\n学科：" + subject + "\n题目：" + question.text);
        messages.put(user);
        body.put("messages", messages);
        body.put("temperature", 0.2);
        body.put("max_tokens", 300);
        body.put("stream", false);
        JSONObject format = new JSONObject();
        format.put("type", "json_object");
        body.put("response_format", format);
        return body.toString();
    }

    private static boolean looksEnglish(String text) {
        if (text == null) {
            return false;
        }
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                latin++;
            }
        }
        return latin >= 3;
    }

    private static String extractContent(String body) throws JSONException {
        JSONObject root = new JSONObject(body);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return null;
        }
        JSONObject first = choices.optJSONObject(0);
        if (first == null) {
            return null;
        }
        JSONObject message = first.optJSONObject("message");
        return message == null ? null : message.optString("content", "");
    }

    /** 去 BOM / 去 ``` 包裹 / 括号配对扫描，取第一个完整 JSON 对象。 */
    static Hint parseHint(String rawContent) {
        if (rawContent == null) {
            return null;
        }
        String text = rawContent.replace("\uFEFF", "").trim();
        if (text.isEmpty()) {
            return null;
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int end = -1;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        if (end < 0) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(text.substring(start, end + 1));
            String hint = obj.optString("hint", "").trim();
            JSONArray stepsArray = obj.optJSONArray("steps");
            List<String> steps = new ArrayList<String>();
            if (stepsArray != null) {
                for (int i = 0; i < stepsArray.length(); i++) {
                    String step = stepsArray.optString(i, "").trim();
                    if (!step.isEmpty()) {
                        steps.add(step);
                    }
                }
            }
            String concept = obj.optString("concept", "").trim();
            String pitfall = obj.optString("pitfall", "").trim();
            Object more = obj.opt("need_more_info");
            boolean needMore = more instanceof Boolean && (Boolean) more;
            return new Hint(hint, steps, concept, pitfall, needMore, false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void finish(String questionText, Hint hint, int reason) {
        List<Callback> callbacks;
        synchronized (PENDING) {
            callbacks = PENDING.remove(questionText);
        }
        if (callbacks == null || callbacks.isEmpty()) {
            return;
        }
        final Hint result = hint;
        final int failReason = reason;
        final List<Callback> targets = new ArrayList<Callback>(callbacks);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Callback callback : targets) {
                    if (result != null) {
                        callback.onHint(result, false);
                    } else {
                        callback.onUnavailable(failReason);
                    }
                }
            }
        });
    }

    private static int reasonForHttp(int code) {
        if (code == 401 || code == 403) {
            return REASON_AUTH;
        }
        if (code == 429) {
            return REASON_RATE;
        }
        if (code >= 500) {
            return REASON_TIMEOUT;
        }
        return REASON_PARSE;
    }

    private static String readStream(InputStream in) throws java.io.IOException {
        if (in == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }
}
