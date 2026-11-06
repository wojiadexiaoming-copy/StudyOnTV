package com.family.quizparent;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 电视端局域网 HTTP 客户端。
 * 只用 java.net + org.json，不依赖任何第三方库，也不依赖 Android 上下文，
 * 方便在 JVM / app_process 里做接口联调测试。
 *
 * v3（真机联调后加固）：电视 Wi-Fi 发送方向会丢大包，典型表现是 HTTP 200 头到了、
 * body 0 字节或被截断。因此：
 * 1) 所有请求带 Accept-Encoding: gzip，能压缩就让电视少发几个字节；
 * 2) 空 body / 截断 / 连接提前关闭 / 读超时都算「可重试错误」，自动重试 2 次，
 *    间隔 1s、2s，且每次重试都重新建连接（电视端响应是 Connection: close）；
 * 3) 不依赖 available()，以实际读到的字节数为准，Content-Length 只作参考。
 */
public final class TvClient {

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 8000;
    /** 统计页数据最大，给 12s。 */
    private static final int STATS_READ_TIMEOUT_MS = 12000;
    private static final int MAX_RETRIES = 2;
    private static final long[] RETRY_DELAY_MS = {1000L, 2000L};
    private static final int MAX_RESPONSE = 64 * 1024;

    /** 重试耗尽后统一给这句中文，界面直接展示并配「重试」按钮。 */
    public static final String INCOMPLETE_MESSAGE = "电视响应不完整，已重试 2 次，请再点一次";

    private final String host;
    private final int port;
    private final String pin;

    public TvClient(String host, int port, String pin) {
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.pin = pin == null ? "" : pin.trim();
    }

    public static final class Response {
        public final int code;
        public final String body;
        /** ok=false 时一定是可直接展示的中文原因。 */
        public final String error;
        public final boolean ok;
        /** 实际发起的请求次数（含重试），1 表示一次成功。 */
        public final int attempts;
        /** true 表示重试耗尽后仍拿到空 body / 截断响应。 */
        public final boolean incomplete;

        private Response(int code, String body, String error, boolean ok, int attempts,
                         boolean incomplete) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.error = error;
            this.ok = ok;
            this.attempts = attempts;
            this.incomplete = incomplete;
        }

        /** HTTP 404：电视端还没实现 v2.8 接口。 */
        public boolean isNotFound() {
            return code == 404;
        }

        /** HTTP 403：PIN 不对。 */
        public boolean isPinWrong() {
            return code == 403;
        }

        public JSONObject json() {
            String text = body.trim();
            if (!text.startsWith("{")) {
                return null;
            }
            try {
                return new JSONObject(text);
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    public Response summary() {
        return request("GET", "/api/summary", null, READ_TIMEOUT_MS);
    }

    public Response settings(JSONObject body) {
        return request("POST", "/api/settings", body == null ? "{}" : body.toString(),
                READ_TIMEOUT_MS);
    }

    public Response pass(String pkg, int minutes) {
        try {
            JSONObject body = new JSONObject();
            body.put("pkg", pkg);
            body.put("minutes", minutes);
            return request("POST", "/api/pass", body.toString(), READ_TIMEOUT_MS);
        } catch (Throwable t) {
            return new Response(-1, "", "请求参数有误：" + t.getMessage(), false, 1, false);
        }
    }

    public Response revoke(String pkg) {
        try {
            JSONObject body = new JSONObject();
            body.put("pkg", pkg);
            return request("POST", "/api/revoke", body.toString(), READ_TIMEOUT_MS);
        } catch (Throwable t) {
            return new Response(-1, "", "请求参数有误：" + t.getMessage(), false, 1, false);
        }
    }

    public Response clearRecords() {
        return request("POST", "/api/clear-records", "{}", READ_TIMEOUT_MS);
    }

    // ---------------- v2.9 统计接口 ----------------

    public Response stats() {
        return request("GET", "/api/stats", null, STATS_READ_TIMEOUT_MS);
    }

    public Response removeQuestion(String id) {
        return idRequest("/api/remove-question", id);
    }

    public Response restoreQuestion(String id) {
        return idRequest("/api/restore-question", id);
    }

    public Response resetStats() {
        return request("POST", "/api/reset-stats", "{}", READ_TIMEOUT_MS);
    }

    private Response idRequest(String path, String id) {
        try {
            JSONObject body = new JSONObject();
            body.put("id", id == null ? "" : id);
            return request("POST", path, body.toString(), READ_TIMEOUT_MS);
        } catch (Throwable t) {
            return new Response(-1, "", "请求参数有误：" + t.getMessage(), false, 1, false);
        }
    }

    /** 题库上传：契约沿用 POST /upload，body 为 JSON 全文。 */
    public Response upload(String jsonText) {
        return request("POST", "/upload", jsonText, READ_TIMEOUT_MS);
    }

    public Response rollback() {
        return request("POST", "/rollback", "{}", READ_TIMEOUT_MS);
    }

    /** 供联调测试覆盖 /（题库页）等任意路径；正式界面只用上面这些方法。 */
    Response requestPath(String method, String path, String body) {
        return request(method, path, body, READ_TIMEOUT_MS);
    }

    /** 响应不完整（空 body / 被截断 / 提前关闭）或读超时，可重试。 */
    private static final class RetryableException extends IOException {
        RetryableException(String message) {
            super(message);
        }
    }

    private Response request(String method, String path, String body, int readTimeoutMs) {
        for (int attempt = 1; attempt <= MAX_RETRIES + 1; attempt++) {
            if (attempt > 1 && !sleep(RETRY_DELAY_MS[attempt - 2])) {
                return new Response(-1, "", "请求被中断，请重试", false, attempt - 1, false);
            }
            HttpURLConnection connection = null;
            boolean gotHeaders = false;
            try {
                URL url = new URL("http://" + host + ":" + port + path);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(readTimeoutMs);
                connection.setRequestMethod(method);
                connection.setRequestProperty("Accept", "application/json, text/plain");
                // 电视端已用 gzip 缓解丢包；不要让系统退化成 identity。
                connection.setRequestProperty("Accept-Encoding", "gzip");
                connection.setRequestProperty("X-Pin", pin);
                connection.setUseCaches(false);

                if (body != null) {
                    byte[] data = body.getBytes("UTF-8");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setFixedLengthStreamingMode(data.length);
                    OutputStream out = connection.getOutputStream();
                    try {
                        out.write(data);
                        out.flush();
                    } finally {
                        out.close();
                    }
                }

                int code = connection.getResponseCode();
                gotHeaders = true;
                String encoding = connection.getHeaderField("Content-Encoding");
                boolean gzipped = encoding != null && encoding.toLowerCase(Locale.US).contains("gzip");
                long declared = parseLength(connection.getHeaderField("Content-Length"));
                InputStream raw = code >= 400 ? connection.getErrorStream()
                        : connection.getInputStream();
                byte[] data = readAll(raw, gzipped);

                if (code >= 200 && code < 300) {
                    String text = toText(data);
                    // 头到了、body 空：电视 Wi-Fi 丢了大包，重试。
                    if (text.trim().isEmpty()) {
                        throw new RetryableException("empty body");
                    }
                    // gzip 时 Content-Length 是压缩后长度，不和明文比；只有明文才比对。
                    if (!gzipped && declared > 0 && data.length < declared) {
                        throw new RetryableException("truncated " + data.length + "/" + declared);
                    }
                    return new Response(code, text, null, true, attempt, false);
                }
                return new Response(code, toText(data), describeHttpError(code, toText(data)),
                        false, attempt, false);
            } catch (UnknownHostException e) {
                return new Response(-1, "", "IP 地址格式不正确，请检查电视 IP", false, attempt, false);
            } catch (SocketTimeoutException e) {
                if (!gotHeaders) {
                    if (attempt <= MAX_RETRIES) {
                        continue;
                    }
                    return new Response(-1, "", "连接电视超时。请确认手机和电视连在同一个 Wi-Fi，"
                            + "并且电视上已经打开「手机连接」页面（局域网服务只在页面打开时运行）",
                            false, attempt, false);
                }
                // 头已经拿到、读 body 超时：典型的大包丢失，重试。
                if (attempt <= MAX_RETRIES) {
                    continue;
                }
                return new Response(-1, "", INCOMPLETE_MESSAGE, false, attempt, true);
            } catch (ConnectException e) {
                if (attempt <= MAX_RETRIES) {
                    continue;
                }
                return new Response(-1, "", "无法连接电视。请检查 IP 和端口是否正确，"
                        + "并确认电视上已经打开「手机连接」页面", false, attempt, false);
            } catch (IOException e) {
                // 连接提前关闭 / gzip 中途 EOF / 半包：都可重试。
                if (attempt <= MAX_RETRIES) {
                    continue;
                }
                return new Response(-1, "", INCOMPLETE_MESSAGE, false, attempt, true);
            } catch (Throwable t) {
                String name = t.getClass().getSimpleName();
                String message = t.getMessage();
                return new Response(-1, "", "网络错误：" + name
                        + (message == null || message.isEmpty() ? "" : " " + message),
                        false, attempt, false);
            } finally {
                if (connection != null) {
                    try {
                        connection.disconnect();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                }
            }
        }
        return new Response(-1, "", INCOMPLETE_MESSAGE, false, MAX_RETRIES + 1, true);
    }

    private static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long parseLength(String value) {
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String describeHttpError(int code, String body) {
        String text = body == null ? "" : body.trim();
        if (code == 403) {
            return "PIN 错误，请检查电视「家长设置」里设置的 4 位密码";
        }
        if (code == 404) {
            return "电视端版本过低，缺少该接口，请升级到 v2.9";
        }
        if (code == 413) {
            return "上传内容超过 1MB 上限";
        }
        if (text.startsWith("{")) {
            try {
                JSONObject json = new JSONObject(text);
                String error = json.optString("error", "");
                if (error != null && !error.trim().isEmpty()) {
                    return error.trim();
                }
            } catch (Throwable ignored) {
                // 回退到通用文案
            }
        }
        if (text.length() > 0 && text.length() <= 120) {
            return text;
        }
        return String.format(Locale.CHINA, "电视端返回错误（HTTP %d）", code);
    }

    /** 以实际读到的字节为准，不用 available()；gzip 由调用方按响应头判断。 */
    private static byte[] readAll(InputStream in, boolean gzipped) throws IOException {
        if (in == null) {
            return new byte[0];
        }
        InputStream source = gzipped ? new GZIPInputStream(in) : in;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            while (buffer.size() < MAX_RESPONSE) {
                int read = source.read(chunk);
                if (read < 0) {
                    break;
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            try {
                source.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static String toText(byte[] data) {
        try {
            String text = new String(data, "UTF-8");
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            return text;
        } catch (Throwable t) {
            return "";
        }
    }
}
