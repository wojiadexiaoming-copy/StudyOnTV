package com.family.quizlauncher;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.system.Os;
import android.system.OsConstants;
import android.text.TextUtils;

import com.family.quizlauncher.ai.ApiKeys;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

/**
 * 极简局域网 HTTP 服务，仅使用 JDK 自带 ServerSocket（Android 没有 com.sun.net.httpserver）。
 *
 * 三种模式：
 * - MODE_BANK：只在「题库管理」页可见时运行，提供 /upload、/rollback；
 * - MODE_AI：只在「AI 解析设置」页可见时运行，提供 /ai；
 * - MODE_CONTROL：v2.8 由前台服务常驻，提供 /api/* 手机端控制接口，同时保留 /upload、/rollback、/ai。
 * 所有端点只在本机局域网内处理，不联网外发。
 */
public class QuestionHttpServer {

    public static final int MODE_BANK = 0;
    public static final int MODE_AI = 1;
    public static final int MODE_CONTROL = 2;

    private static final int DEFAULT_PORT = 8080;
    private static final int PORT_TRIES = 10;
    private static final int MAX_BODY = QuestionImporter.MAX_BYTES;
    private static final int MAX_RECORDS = 60;
    /** 每条连接一个线程，用 ThreadLocal 记住该请求是否接受 gzip。 */
    private static final ThreadLocal<Boolean> S_GZIP = new ThreadLocal<Boolean>();

    private final Context context;
    private final int mode;
    private ServerSocket serverSocket;
    private Thread thread;
    private volatile boolean running;
    private int port = DEFAULT_PORT;

    public QuestionHttpServer(Context context) {
        this(context, MODE_BANK);
    }

    public QuestionHttpServer(Context context, boolean aiPage) {
        this(context, aiPage ? MODE_AI : MODE_BANK);
    }

    public QuestionHttpServer(Context context, int mode) {
        this.context = context.getApplicationContext();
        this.mode = mode;
    }

    public synchronized boolean start() {
        if (running) {
            return true;
        }
        for (int i = 0; i < PORT_TRIES; i++) {
            ServerSocket candidate = null;
            try {
                candidate = new ServerSocket();
                candidate.setReuseAddress(true);
                candidate.bind(new InetSocketAddress(DEFAULT_PORT + i), 4);
                serverSocket = candidate;
                port = DEFAULT_PORT + i;
                break;
            } catch (Exception ignored) {
                // 端口被占用就顺延
                if (candidate != null) {
                    try {
                        candidate.close();
                    } catch (IOException closeError) {
                        // ignore
                    }
                }
            }
        }
        if (serverSocket == null) {
            return false;
        }
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "quiz-http");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // ignore
            }
            serverSocket = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    private void acceptLoop() {
        while (running) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                socket.setSoTimeout(15000);
                final Socket conn = socket;
                // 大响应要分块慢发（单条可能要 2~3 秒），不能阻塞其他请求。
                socket = null;
                Thread worker = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            handle(conn);
                        } catch (Exception ignored) {
                            // 单条连接出错不影响服务
                        } finally {
                            try {
                                conn.close();
                            } catch (IOException ignored) {
                                // ignore
                            }
                        }
                    }
                }, "quiz-http-req");
                worker.setDaemon(true);
                worker.start();
            } catch (Exception ignored) {
                if (!running) {
                    break;
                }
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // ignore
                    }
                }
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        // 该电视的 Wi‑Fi 发送端对大包/连续包会静默丢弃：客户端能收到第一个报文
        // （响应头），后续 body 段全部收不到；本机回环则正常。
        // 这里把本连接的 MSS 压到 256 字节（绕开 TSO/大包丢段），并关掉积压缓冲。
        tuneSocket(socket);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        String head = readHead(in);
        if (head == null) {
            return;
        }
        String[] lines = head.split("\r\n");
        if (lines.length == 0) {
            respond(out, 400, "text/plain", "Bad Request");
            return;
        }
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) {
            respond(out, 400, "text/plain", "Bad Request");
            return;
        }
        String method = requestLine[0].toUpperCase(Locale.US);
        String path = requestLine[1];
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }

        Map<String, String> headers = new HashMap<String, String>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.US),
                        lines[i].substring(colon + 1).trim());
            }
        }
        // 这条电视的 Wi‑Fi 发大包极不稳定，压缩后报文数少一半以上，成功率高很多。
        String acceptEncoding = headers.get("accept-encoding");
        S_GZIP.set(acceptEncoding != null && acceptEncoding.toLowerCase(Locale.US).contains("gzip"));

        long contentLength = 0;
        String lengthHeader = headers.get("content-length");
        if (lengthHeader != null) {
            try {
                contentLength = Long.parseLong(lengthHeader);
            } catch (NumberFormatException ignored) {
                contentLength = 0;
            }
        }
        if (contentLength > MAX_BODY) {
            respond(out, 413, "text/plain", "上传内容超过 1MB 上限");
            return;
        }
        byte[] body = readBody(in, (int) contentLength);
        // v3.1：登录退避按「IP + User-Agent」粗略聚合；不记录任何内容到日志。
        String remoteIp = "?";
        try {
            remoteIp = socket.getInetAddress().getHostAddress();
        } catch (Throwable ignored) {
            // 取不到 IP 就退化为单一客户端
        }
        String userAgent = headers.get("user-agent");
        String clientKey = remoteIp + "|" + (userAgent == null ? "" : userAgent);

        if ("GET".equals(method)) {
            if (mode == MODE_AI) {
                if ("/".equals(path) || "/ai".equals(path) || "/ai/".equals(path)) {
                    respond(out, 200, "text/html", buildAiPage());
                } else if ("/ai/status".equals(path)) {
                    respond(out, 200, "application/json", buildAiStatusJson());
                } else {
                    respond(out, 404, "text/plain", "Not Found");
                }
                return;
            }
            if (mode == MODE_CONTROL) {
                if ("/api/summary".equals(path)) {
                    // v3.1：带上 X-Token / X-Pin 时校验；两者都不带时沿用 v3.0 的公开只读，
                    // 保证安卓平板 App 的轮询不被破坏。
                    if (!authOptional(headers, out)) {
                        return;
                    }
                    try {
                        respond(out, 200, "application/json", buildSummary().toString());
                    } catch (Throwable t) {
                        respondJson(out, 500, "生成摘要失败");
                    }
                } else if ("/api/stats".equals(path)) {
                    if (!authOptional(headers, out)) {
                        return;
                    }
                    try {
                        respond(out, 200, "application/json", buildStats().toString());
                    } catch (Throwable t) {
                        respondJson(out, 500, "生成题库统计失败");
                    }
                } else if ("/api/session".equals(path)) {
                    String token = headers.get("x-token");
                    long seconds = TokenAuth.get().sessionSeconds(
                            token == null ? null : token.trim());
                    if (seconds <= 0) {
                        respondJson(out, 401, "登录已过期，请重新登录");
                    } else {
                        respond(out, 200, "application/json",
                                "{\"ok\":true,\"expiresIn\":" + seconds + "}");
                    }
                } else if ("/".equals(path) || "/index.html".equals(path)
                        || "/admin".equals(path) || "/admin/".equals(path)) {
                    respond(out, 200, "text/html", buildAdminPage());
                } else if ("/app.css".equals(path)) {
                    respond(out, 200, "text/css", readAssetCached("web/app.css"));
                } else if ("/app.js".equals(path)) {
                    respond(out, 200, "application/javascript", readAssetCached("web/app.js"));
                } else if ("/status".equals(path)) {
                    respond(out, 200, "application/json", buildStatusJson());
                } else if ("/api/tpl".equals(path) || "/api/prompt".equals(path)) {
                    if (!authOptional(headers, out)) {
                        return;
                    }
                    try {
                        String name = "/api/tpl".equals(path) ? "web/tpl.txt" : "web/prompt.txt";
                        JSONObject json = new JSONObject();
                        json.put("text", readAssetCached(name));
                        respond(out, 200, "application/json", json.toString());
                    } catch (Throwable t) {
                        respondJson(out, 404, "资源不存在");
                    }
                } else if ("/ai".equals(path) || "/ai/".equals(path)) {
                    respond(out, 200, "text/html", buildAiPage());
                } else if ("/ai/status".equals(path)) {
                    respond(out, 200, "application/json", buildAiStatusJson());
                } else {
                    respond(out, 404, "text/plain", "Not Found");
                }
                return;
            }
            if ("/".equals(path) || "/index.html".equals(path)) {
                respond(out, 200, "text/html", buildPage());
            } else if ("/status".equals(path)) {
                respond(out, 200, "application/json", buildStatusJson());
            } else {
                respond(out, 404, "text/plain", "Not Found");
            }
            return;
        }

        if ("POST".equals(method)) {
            // v2.8：手机端 /api/* 走独立的 JSON 错误格式与 PIN 校验（未设 PIN 一律 403）。
            // v3.1：/api/login、/api/logout 在 handleControlApi 内部单独处理，其余接口
            // 统一接受 X-Token 或 X-Pin。
            if (mode == MODE_CONTROL && path.startsWith("/api/")) {
                handleControlApi(path, headers, body, out, clientKey);
                return;
            }
            // v3.1：/upload、/rollback、/ai* 同样扩展为 Token 或 PIN。
            if (!authLegacy(headers, out)) {
                return;
            }
            if (mode == MODE_AI) {
                if ("/ai/save".equals(path)) {
                    handleAiSave(body, out);
                    return;
                }
                if ("/ai/test".equals(path)) {
                    handleAiTest(body, out);
                    return;
                }
                if ("/ai/clear".equals(path)) {
                    handleAiClear(out);
                    return;
                }
                respond(out, 404, "text/plain", "Not Found");
                return;
            }
            if ("/upload".equals(path)) {
                QuestionImporter.Result result = QuestionImporter.importJson(context,
                        new String(body, "UTF-8"));
                respond(out, result.ok ? 200 : 400, "text/plain", result.message);
                return;
            }
            if ("/rollback".equals(path)) {
                QuestionImporter.Result result = QuestionImporter.rollback(context);
                respond(out, result.ok ? 200 : 400, "text/plain", result.message);
                return;
            }
            if ("/ai/save".equals(path)) {
                handleAiSave(body, out);
                return;
            }
            if ("/ai/test".equals(path)) {
                handleAiTest(body, out);
                return;
            }
            if ("/ai/clear".equals(path)) {
                handleAiClear(out);
                return;
            }
            respond(out, 404, "text/plain", "Not Found");
            return;
        }

        respond(out, 405, "text/plain", "Method Not Allowed");
    }

    // ---------------- v2.8 手机端控制接口（/api/*） ----------------

    private void handleControlApi(String path, Map<String, String> headers, byte[] body,
                                  OutputStream out, String clientKey) throws IOException {
        // v3.1：登录本身不需要凭据，登出只认令牌。
        if ("/api/login".equals(path)) {
            handleApiLogin(body, out, clientKey);
            return;
        }
        if ("/api/logout".equals(path)) {
            handleApiLogout(headers, out);
            return;
        }
        if (!authRequired(headers, out)) {
            return;
        }
        try {
            Prefs prefs = Prefs.get(context);
            if ("/api/settings".equals(path)) {
                handleApiSettings(body, out);
                return;
            }
            if ("/api/strict".equals(path)) {
                handleApiStrict(body, out);
                return;
            }
            if ("/api/pass".equals(path)) {
                handleApiPass(body, out);
                return;
            }
            if ("/api/revoke".equals(path)) {
                handleApiRevoke(body, out);
                return;
            }
            if ("/api/clear-records".equals(path)) {
                prefs.clearRecords();
                respondSummary(out);
                return;
            }
            if ("/api/remove-question".equals(path)) {
                handleApiRemoveQuestion(body, out);
                return;
            }
            if ("/api/restore-question".equals(path)) {
                handleApiRestoreQuestion(body, out);
                return;
            }
            if ("/api/reset-stats".equals(path)) {
                QuestionStats.get(context).resetCounters();
                respondSummary(out);
                return;
            }
            respondJson(out, 404, "未知接口：" + path);
        } catch (org.json.JSONException e) {
            respondJson(out, 400, "请求内容不是合法的 JSON 对象");
        }
    }

    // ---------------- v3.1 网页版家长控制台：认证 ----------------

    /**
     * 写操作与敏感接口：令牌优先，其次兼容安卓平板 App 的 X-Pin。
     * 令牌无效/过期返回 401 JSON，前端据此自动跳回登录页。
     */
    private boolean authRequired(Map<String, String> headers, OutputStream out) throws IOException {
        String token = headers.get("x-token");
        if (token != null && !token.trim().isEmpty()) {
            if (TokenAuth.get().sessionSeconds(token.trim()) > 0) {
                return true;
            }
            respondTokenExpired(out);
            return false;
        }
        Prefs prefs = Prefs.get(context);
        if (!prefs.hasPin()) {
            respondJson(out, 403, "电视上还没有设置家长密码，请先在「家长设置」里设置 4 位 PIN");
            return false;
        }
        String pin = headers.get("x-pin");
        if (pin == null || !pin.trim().equals(prefs.getPin())) {
            respondJson(out, 403, "PIN 错误，请检查电视上的 4 位家长密码");
            return false;
        }
        return true;
    }

    /**
     * 只读接口（summary/stats）：带了凭据就校验；完全不带凭据时沿用 v3.0 的公开只读，
     * 避免破坏安卓平板 App 的既有轮询。
     */
    private boolean authOptional(Map<String, String> headers, OutputStream out) throws IOException {
        String token = headers.get("x-token");
        if (token != null && !token.trim().isEmpty()) {
            if (TokenAuth.get().sessionSeconds(token.trim()) > 0) {
                return true;
            }
            respondTokenExpired(out);
            return false;
        }
        String pin = headers.get("x-pin");
        if (pin == null) {
            return true;
        }
        Prefs prefs = Prefs.get(context);
        if (prefs.hasPin() && pin.trim().equals(prefs.getPin())) {
            return true;
        }
        respondJson(out, 403, "PIN 错误，请检查电视上的 4 位家长密码");
        return false;
    }

    /** /upload、/rollback、/ai*：错误沿用 v2.8 的 text/plain，令牌过期才回 JSON。 */
    private boolean authLegacy(Map<String, String> headers, OutputStream out) throws IOException {
        String token = headers.get("x-token");
        if (token != null && !token.trim().isEmpty()) {
            if (TokenAuth.get().sessionSeconds(token.trim()) > 0) {
                return true;
            }
            respondTokenExpired(out);
            return false;
        }
        Prefs prefs = Prefs.get(context);
        if (!prefs.hasPin()) {
            respond(out, 400, "text/plain", "电视上还没有设置家长密码，请先在「家长设置」里设置");
            return false;
        }
        String pin = headers.get("x-pin");
        if (pin == null || !pin.trim().equals(prefs.getPin())) {
            respond(out, 403, "text/plain", "PIN 错误，请检查电视上设置的 4 位密码");
            return false;
        }
        return true;
    }

    private static void respondTokenExpired(OutputStream out) throws IOException {
        respondJson(out, 401, "登录已过期，请重新登录");
    }

    /** POST /api/login {"pin":"<家长PIN>"}，带 IP+UA 维度的 5 次失败锁 60 秒。 */
    private void handleApiLogin(byte[] body, OutputStream out, String clientKey) throws IOException {
        Prefs prefs = Prefs.get(context);
        if (!prefs.hasPin()) {
            respondJson(out, 403, "电视上还没有设置家长密码，请先在「家长设置」里设置 4 位 PIN");
            return;
        }
        JSONObject input;
        try {
            input = parseJson(body);
        } catch (org.json.JSONException e) {
            respondJson(out, 400, "请求内容不是合法的 JSON 对象");
            return;
        }
        String pin = input.optString("pin", "").trim();
        if (pin.isEmpty()) {
            respondJson(out, 400, "缺少 pin（4 位家长密码）");
            return;
        }
        TokenAuth.Outcome outcome = TokenAuth.get().login(clientKey, pin, prefs.getPin());
        JSONObject json = new JSONObject();
        try {
            if (outcome.code == TokenAuth.LOCKED) {
                int retry = outcome.retryAfter < 1 ? 1 : outcome.retryAfter;
                json.put("ok", false);
                json.put("error", "尝试次数过多，请 " + retry + " 秒后再试");
                json.put("retryAfter", retry);
                respond(out, 429, "application/json", json.toString());
                return;
            }
            if (outcome.code == TokenAuth.BAD_PIN) {
                json.put("ok", false);
                json.put("error", "密码错误");
                json.put("remaining", outcome.remaining);
                respond(out, 401, "application/json", json.toString());
                return;
            }
            json.put("ok", true);
            json.put("token", outcome.token);
            json.put("expiresIn", 1800);
            respond(out, 200, "application/json", json.toString());
        } catch (Throwable t) {
            respondJson(out, 500, "登录失败");
        }
    }

    private void handleApiLogout(Map<String, String> headers, OutputStream out) throws IOException {
        String token = headers.get("x-token");
        if (token == null || token.trim().isEmpty()
                || TokenAuth.get().sessionSeconds(token.trim()) <= 0) {
            respondTokenExpired(out);
            return;
        }
        TokenAuth.get().logout(token.trim());
        respond(out, 200, "application/json", "{\"ok\":true}");
    }

    private void handleApiSettings(byte[] body, OutputStream out)
            throws IOException, org.json.JSONException {
        Prefs prefs = Prefs.get(context);
        JSONObject input = parseJson(body);
        if (input.has("minutes") && !input.isNull("minutes")) {
            Object raw = input.opt("minutes");
            if (!(raw instanceof Number)) {
                respondJson(out, 400, "minutes 必须是 1~120 的整数");
                return;
            }
            int minutes = ((Number) raw).intValue();
            if (minutes < 1 || minutes > 120) {
                respondJson(out, 400, "minutes 必须在 1~120 之间");
                return;
            }
            prefs.setMinutes(minutes);
        }
        if (input.has("passLine") && !input.isNull("passLine")) {
            Object raw = input.opt("passLine");
            if (!(raw instanceof Number)) {
                respondJson(out, 400, "passLine 必须是 1~5 的整数");
                return;
            }
            int passLine = ((Number) raw).intValue();
            if (passLine < 1 || passLine > 5) {
                respondJson(out, 400, "passLine 必须在 1~5 之间");
                return;
            }
            prefs.setRequired(passLine);
        }
        if (input.has("restricted") && !input.isNull("restricted")) {
            JSONArray array = input.optJSONArray("restricted");
            if (array == null) {
                respondJson(out, 400, "restricted 必须是包名数组");
                return;
            }
            Set<String> packages = new TreeSet<String>();
            for (int i = 0; i < array.length(); i++) {
                String pkg = array.optString(i, "").trim();
                if (pkg.isEmpty()) {
                    respondJson(out, 400, "restricted 里有空的包名");
                    return;
                }
                packages.add(pkg);
            }
            prefs.replaceRestricted(packages);
        }
        if (input.has("masteredThreshold") && !input.isNull("masteredThreshold")) {
            Object raw = input.opt("masteredThreshold");
            if (!(raw instanceof Number)) {
                respondJson(out, 400, "masteredThreshold 必须是 1~10 的整数");
                return;
            }
            int threshold = ((Number) raw).intValue();
            if (threshold < 1 || threshold > 10) {
                respondJson(out, 400, "masteredThreshold 必须在 1~10 之间");
                return;
            }
            prefs.setMasteredThreshold(threshold);
        }
        if (input.has("autoRemove") && !input.isNull("autoRemove")) {
            Object raw = input.opt("autoRemove");
            if (!(raw instanceof Boolean)) {
                respondJson(out, 400, "autoRemove 必须是 true 或 false");
                return;
            }
            prefs.setAutoRemove((Boolean) raw);
        }
        if (input.has("enabledSubjects") && !input.isNull("enabledSubjects")) {
            JSONArray array = input.optJSONArray("enabledSubjects");
            if (array == null) {
                respondJson(out, 400, "enabledSubjects 必须是学科 key 数组（空数组=全部启用）");
                return;
            }
            QuestionBank.Bank bank = QuestionBank.loadBank(context);
            Set<String> keys = new TreeSet<String>();
            for (int i = 0; i < array.length(); i++) {
                String key = array.optString(i, "").trim();
                if (key.isEmpty()) {
                    respondJson(out, 400, "enabledSubjects 里有空的学科 key");
                    return;
                }
                if (bank.find(key) == null) {
                    respondJson(out, 400, "未知学科：" + key);
                    return;
                }
                keys.add(key);
            }
            prefs.setEnabledSubjects(keys);
        }
        // v3.0：严格模式开关与白名单（与 /api/strict 等价，方便一次写多项设置）
        if (input.has("strictMode") && !input.isNull("strictMode")) {
            Object raw = input.opt("strictMode");
            if (!(raw instanceof Boolean)) {
                respondJson(out, 400, "strictMode 必须是 true 或 false");
                return;
            }
            prefs.setStrictMode((Boolean) raw);
        }
        if (input.has("allowList") && !input.isNull("allowList")) {
            JSONArray array = input.optJSONArray("allowList");
            if (array == null) {
                respondJson(out, 400, "allowList 必须是包名数组（支持 com.android.providers.* 前缀）");
                return;
            }
            Set<String> allow = new TreeSet<String>();
            for (int i = 0; i < array.length(); i++) {
                String pkg = array.optString(i, "").trim();
                if (pkg.isEmpty()) {
                    respondJson(out, 400, "allowList 里有空的包名");
                    return;
                }
                allow.add(pkg);
            }
            if (allow.isEmpty()) {
                respondJson(out, 400, "allowList 不能为空；清空白名单请用 allowList 省略或调用恢复默认");
                return;
            }
            prefs.setAllowList(allow);
        }
        respondSummary(out);
    }

    /** v3.0：POST /api/strict {"enabled":bool} —— 单独切换严格模式。 */
    private void handleApiStrict(byte[] body, OutputStream out)
            throws IOException, org.json.JSONException {
        Prefs prefs = Prefs.get(context);
        JSONObject input = parseJson(body);
        Object raw = input.opt("enabled");
        if (!(raw instanceof Boolean)) {
            respondJson(out, 400, "enabled 必须是 true 或 false");
            return;
        }
        prefs.setStrictMode((Boolean) raw);
        respondSummary(out);
    }

    private void handleApiPass(byte[] body, OutputStream out)
            throws IOException, org.json.JSONException {
        Prefs prefs = Prefs.get(context);
        JSONObject input = parseJson(body);
        String pkg = input.optString("pkg", "").trim();
        if (pkg.isEmpty()) {
            respondJson(out, 400, "缺少 pkg（应用包名）");
            return;
        }
        int minutes = prefs.getMinutes();
        Object raw = input.opt("minutes");
        if (raw instanceof Number) {
            minutes = ((Number) raw).intValue();
        }
        if (minutes <= 0) {
            minutes = prefs.getMinutes();
        }
        if (minutes > 120) {
            minutes = 120;
        }
        // 沿用 Prefs.grantPass：带开机会话烙印，重启后作废；不修改家长的 minutes 设置。
        prefs.grantPass(pkg, minutes);
        respondSummary(out);
    }

    private void handleApiRevoke(byte[] body, OutputStream out)
            throws IOException, org.json.JSONException {
        JSONObject input = parseJson(body);
        String pkg = input.optString("pkg", "").trim();
        if (pkg.isEmpty()) {
            respondJson(out, 400, "缺少 pkg（应用包名）");
            return;
        }
        Prefs.get(context).clearPass(pkg);
        respondSummary(out);
    }

    /** v2.9：手动把某道题从当前题库移出（写入 mastered.json 备份）。 */
    private void handleApiRemoveQuestion(byte[] body, OutputStream out)
            throws IOException, org.json.JSONException {
        JSONObject input = parseJson(body);
        String id = input.optString("id", "").trim();
        if (id.isEmpty()) {
            respondJson(out, 400, "缺少 id（题目 id）");
            return;
        }
        QuestionBank.Bank bank = QuestionBank.loadBank(context);
        Question question = QuestionBank.findById(bank, id);
        if (question == null) {
            respondJson(out, 400, "题库里没有这道题：" + id);
            return;
        }
        QuestionStats.get(context).removeQuestion(question);
        respondSummary(out);
    }

    /** v2.9：恢复一道已移出的题。 */
    private void handleApiRestoreQuestion(byte[] body, OutputStream out)
            throws IOException, org.json.JSONException {
        JSONObject input = parseJson(body);
        String id = input.optString("id", "").trim();
        if (id.isEmpty()) {
            respondJson(out, 400, "缺少 id（题目 id）");
            return;
        }
        if (!QuestionStats.get(context).restore(id)) {
            respondJson(out, 400, "这道题不在已移出列表里：" + id);
            return;
        }
        respondSummary(out);
    }

    private static JSONObject parseJson(byte[] body) throws org.json.JSONException {
        String text = new String(body, java.nio.charset.Charset.forName("UTF-8")).trim();
        if (text.isEmpty()) {
            throw new org.json.JSONException("empty body");
        }
        return new JSONObject(text);
    }

    private void respondSummary(OutputStream out) throws IOException {
        try {
            JSONObject summary = buildSummary();
            summary.put("ok", true);
            respond(out, 200, "application/json", summary.toString());
        } catch (Throwable t) {
            respondJson(out, 500, "生成摘要失败");
        }
    }

    private static void respondJson(OutputStream out, int code, String error) throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("ok", false);
            json.put("error", error);
        } catch (Throwable ignored) {
            // JSONObject.put(String, Object) 实际不会失败
        }
        respond(out, code, "application/json", json.toString());
    }

    /** GET /api/summary 与写操作成功后的统一摘要（不含任何 Key）。 */
    private JSONObject buildSummary() throws org.json.JSONException {
        Prefs prefs = Prefs.get(context);
        JSONObject json = new JSONObject();
        json.put("app", "quizlauncher");
        json.put("version", appVersion());
        json.put("port", port);
        json.put("minutes", prefs.getMinutes());
        json.put("passLine", prefs.getRequired());

        JSONArray restricted = new JSONArray();
        for (String pkg : new TreeSet<String>(prefs.getRestricted())) {
            restricted.put(pkg);
        }
        json.put("restricted", restricted);
        // v3.1：给网页版控制台回显「包名 + 友好名」；老客户端忽略该字段即可。
        JSONObject restrictedLabels = new JSONObject();
        for (String pkg : new TreeSet<String>(prefs.getRestricted())) {
            restrictedLabels.put(pkg, appLabel(pkg));
        }
        json.put("restrictedLabels", restrictedLabels);

        JSONArray passes = new JSONArray();
        for (Map.Entry<String, Long> entry : prefs.listActivePasses().entrySet()) {
            JSONObject item = new JSONObject();
            item.put("pkg", entry.getKey());
            item.put("remainMs", entry.getValue());
            passes.put(item);
        }
        json.put("passes", passes);

        File ext = QuestionImporter.externalFile(context);
        boolean local = ext != null && ext.isFile();
        if (!local) {
            local = !TextUtils.isEmpty(prefs.getCustomQuestions());
        }
        QuestionBank.Bank bank = QuestionBank.loadBank(context);
        JSONObject questions = new JSONObject();
        questions.put("count", bank.total());
        questions.put("source", local ? "local" : "builtin");
        questions.put("title", bankTitle(ext, local));
        json.put("questions", questions);

        // v2.9：按学科汇总 + 掌握相关设置（字段契约与手机端对齐）
        json.put("subjects", buildSubjectSummary(bank));
        JSONObject stats = new JSONObject();
        stats.put("threshold", prefs.getMasteredThreshold());
        stats.put("autoRemove", prefs.isAutoRemove());
        stats.put("enabledSubjects", buildEnabledSubjects(prefs));
        // v3.0：严格模式与白名单（供手机端回显）
        stats.put("strictMode", prefs.isStrictMode());
        stats.put("allowList", buildAllowList(prefs));
        // v3.1：内置硬白名单（桌面/设置/系统界面等），网页端只读展示，避免自锁。
        stats.put("hardAllow", buildHardAllow());
        json.put("stats", stats);

        JSONArray records = new JSONArray();
        JSONArray stored = prefs.getRecords();
        int limit = Math.min(stored.length(), MAX_RECORDS);
        for (int i = 0; i < limit; i++) {
            JSONObject old = stored.optJSONObject(i);
            if (old == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("ts", old.optLong("ts", old.optLong("t", 0L)));
            item.put("pkg", old.optString("pkg", ""));
            item.put("label", old.optString("label", ""));
            item.put("correct", old.optInt("correct", 0));
            item.put("total", old.optInt("total", 0));
            item.put("pass", old.optBoolean("pass", false));
            records.put(item);
        }
        json.put("records", records);
        return json;
    }

    /** v2.9：GET /api/stats —— 按学科汇总 + 逐题统计（题干截断 30 字）。 */
    private JSONObject buildStats() throws org.json.JSONException {
        Prefs prefs = Prefs.get(context);
        QuestionStats stats = QuestionStats.get(context);
        QuestionBank.Bank bank = QuestionBank.loadBank(context);

        JSONObject root = new JSONObject();
        root.put("subjects", buildSubjectSummary(bank));
        root.put("threshold", prefs.getMasteredThreshold());
        root.put("autoRemove", prefs.isAutoRemove());
        root.put("enabledSubjects", buildEnabledSubjects(prefs));

        JSONArray questions = new JSONArray();
        for (QuestionBank.Subject subject : bank.subjects) {
            for (Question question : subject.questions) {
                JSONObject item = new JSONObject();
                item.put("id", question.id);
                item.put("subject", subject.key);
                item.put("q", QuestionStats.clip30(question.text));
                QuestionStats.Entry entry = stats.getEntry(question.id);
                item.put("answered", entry.answered);
                item.put("correct", entry.correct);
                item.put("wrong", entry.wrong);
                item.put("mastered", stats.isMastered(question.id));
                item.put("removed", stats.isRemoved(question.id));
                questions.put(item);
            }
        }
        root.put("questions", questions);
        return root;
    }

    /** 学科汇总：key/name/count/mastered/removed（removed 为 v2.9 额外字段）。 */
    private JSONArray buildSubjectSummary(QuestionBank.Bank bank) throws org.json.JSONException {
        QuestionStats stats = QuestionStats.get(context);
        JSONArray subjects = new JSONArray();
        for (QuestionBank.Subject subject : bank.subjects) {
            JSONObject item = new JSONObject();
            item.put("key", subject.key);
            item.put("name", subject.name);
            item.put("count", subject.questions.size());
            item.put("mastered", stats.masteredCount(subject.questions));
            item.put("removed", stats.removedCount(subject.questions));
            subjects.put(item);
        }
        return subjects;
    }

    /** enabledSubjects：空数组表示全部启用。 */
    private static JSONArray buildEnabledSubjects(Prefs prefs) throws org.json.JSONException {
        JSONArray array = new JSONArray();
        for (String key : new TreeSet<String>(prefs.getEnabledSubjects())) {
            array.put(key);
        }
        return array;
    }

    /** v3.1：把包名解析成应用友好名（取不到就原样返回包名）。 */
    private String appLabel(String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null && label.length() > 0) {
                return label.toString().trim();
            }
        } catch (Throwable ignored) {
            // 包不存在或无权限读取时退回包名
        }
        return packageName;
    }

    /** v3.0：严格模式白名单，按包名排序回显。 */
    private static JSONArray buildAllowList(Prefs prefs) throws org.json.JSONException {
        JSONArray array = new JSONArray();
        for (String pkg : new TreeSet<String>(prefs.getAllowList())) {
            array.put(pkg);
        }
        return array;
    }

    /** v3.1：内置硬白名单（只读），桌面/设置/系统界面/输入法等必须始终放行。 */
    private static JSONArray buildHardAllow() throws org.json.JSONException {
        JSONArray array = new JSONArray();
        for (String entry : new TreeSet<String>(AppRepository.defaultAllowList())) {
            array.put(entry);
        }
        return array;
    }

    /** 题库标题：导入题库优先取 JSON 里的 title，其次 version；没有就按来源给中文名。 */
    private String bankTitle(File ext, boolean local) {
        if (local && ext != null && ext.isFile()) {
            try {
                JSONObject root = new JSONObject(QuestionImporter.readText(ext));
                String title = root.optString("title", "").trim();
                if (!title.isEmpty()) {
                    return title;
                }
                String version = root.optString("version", "").trim();
                if (!version.isEmpty()) {
                    return "题库 " + version;
                }
            } catch (Throwable ignored) {
                // 数组格式或读取失败时退回默认标题
            }
        }
        return local ? "本地题库" : "内置题库";
    }

    private String appVersion() {
        try {
            String name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return TextUtils.isEmpty(name) ? "3.3" : name;
        } catch (Throwable t) {
            return "3.3";
        }
    }

    private void handleAiClear(OutputStream out) throws IOException {
        ApiKeys.clearAll(context);
        Prefs.get(context).setAiTest("llm", "");
        Prefs.get(context).setAiTest("tts", "");
        respond(out, 200, "application/json",
                ApiKeys.hasLlmKey(context) || ApiKeys.hasTtsKey(context)
                        ? "{\"ok\":true,\"message\":\"已清除填写的 Key，回落到内置默认\"}"
                        : "{\"ok\":true,\"message\":\"已清除填写的 Key（本机没有内置默认）\"}");
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] last = new byte[4];
        int count = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            buffer.write(b);
            last[0] = last[1];
            last[1] = last[2];
            last[2] = last[3];
            last[3] = (byte) b;
            count++;
            if (count >= 4 && last[0] == 13 && last[1] == 10 && last[2] == 13 && last[3] == 10) {
                break;
            }
            if (buffer.size() > 16384) {
                return null;
            }
        }
        return buffer.toString("UTF-8");
    }

    private static byte[] readBody(InputStream in, int length) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(body, offset, length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return body;
    }

    /**
     * v3.3 真机标定后的发送节奏：单块 256B、块间 150ms、响应头单独一个报文。
     *
     * 依据（电视 192.168.1.50 上逐组实测，PC curl 连打）：
     * - 单次写入 >256B 会被内核拆成多段，首块之后整条连接停住：480B/12ms、480B/60ms、
     *   480B/150ms、600B/150ms 全部只收到 0~960B，100% 失败；320B 同样失败。
     * - 响应头与首块合并写：256B/150ms 也仅 3.6KB 即停。
     * - 头单独写 + 256B/150ms：完整 10,356B / 约 6.1s，10 次中 8 次完整。
     * - 256B/60ms、200ms、260ms 均更差或等价，无提速价值。
     * 因此这里固定为唯一可跑完的组合，不再做运行时标定。
     */
    private static final int CHUNK_BYTES = 256;
    private static final long CHUNK_GAP_MS = 150L;

    private static void respond(OutputStream out, int code, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        byte[] payload = bytes;
        boolean gzip = Boolean.TRUE.equals(S_GZIP.get()) && bytes.length > 400;
        if (gzip) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            GZIPOutputStream zip = new GZIPOutputStream(buffer);
            zip.write(bytes);
            zip.close();
            payload = buffer.toByteArray();
        }
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        header.append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n");
        if (gzip) {
            header.append("Content-Encoding: gzip\r\n");
            header.append("Vary: Accept-Encoding\r\n");
        }
        header.append("Content-Length: ").append(payload.length).append("\r\n");
        header.append("Connection: close\r\n");
        header.append("Cache-Control: no-store\r\n\r\n");
        byte[] head = header.toString().getBytes("UTF-8");
        if (head.length + payload.length <= 500) {
            // 极小响应（/status 等）：头 + body 合成一次 ≤500B 的小写入，最稳。
            out.write(head);
            out.write(payload);
            out.flush();
            return;
        }
        int offset = 0;
        // 头单独一个报文：实测头与首块合并写会放大首包丢段概率。
        out.write(head);
        out.flush();
        while (offset < payload.length) {
            int len = Math.min(CHUNK_BYTES, payload.length - offset);
            out.write(payload, offset, len);
            out.flush();
            offset += len;
            if (offset >= payload.length) {
                break;
            }
            try {
                Thread.sleep(CHUNK_GAP_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        out.flush();
    }

    /**
     * 压低本连接的最大报文段长度。
     *
     * 这台电视（Hi3751V810 + Android 8）在跨机发送时，第一个报文能到，后续报文
     * 全部静默丢失（PC/平板 curl 只拿到响应头、body 0 字节，本机环回正常）。
     * 把 TCP_MAXSEG 设小后，内核不再生成大段/GSO 报文，可绕开该发送缺陷。
     * 只作用于本进程的这条连接，不修改设备的任何网络配置。
     */
    private static void tuneSocket(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            socket.setSendBufferSize(8192);
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            // 让 close() 等到数据真正发出去再返回，避免还没发完就关闭连接（body 被截断）。
            socket.setSoLinger(true, 3);
        } catch (Throwable ignored) {
            // ignore
        }
        try {
            // 只取 fd 用于 setsockopt，绝不能像 ParcelFileDescriptor.fromSocket() 那样
            // 创建一个「拥有该 fd」的包装对象：它被 GC 回收时会 close 掉 fd，
            // 而 Linux 会复用 fd 号，于是下一次连接会被误关（表现为响应只发出一块就断）。
            FileDescriptor fd = socketFd(socket);
            if (fd != null) {
                // Linux 上 TCP_MAXSEG = 2；Android SDK 未公开该常量，这里直接用数值。
                Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, 2, 256);
            }
        } catch (Throwable ignored) {
            // 老 ROM 可能不支持，忽略
        }
    }

    private static volatile boolean fdFieldReady;
    private static java.lang.reflect.Field socketImplField;
    private static java.lang.reflect.Field socketImplFdField;

    /** 通过反射拿到 socket 自身的 FileDescriptor（不创建任何拥有者，不会误关连接）。 */
    private static FileDescriptor socketFd(Socket socket) {
        try {
            if (!fdFieldReady) {
                socketImplField = Socket.class.getDeclaredField("impl");
                socketImplField.setAccessible(true);
                java.lang.reflect.Field fdField = null;
                Class<?> type = Class.forName("java.net.SocketImpl");
                while (type != null && fdField == null) {
                    try {
                        fdField = type.getDeclaredField("fd");
                    } catch (NoSuchFieldException notFound) {
                        type = type.getSuperclass();
                    }
                }
                if (fdField == null) {
                    return null;
                }
                fdField.setAccessible(true);
                socketImplFdField = fdField;
                fdFieldReady = true;
            }
            Object impl = socketImplField.get(socket);
            return impl == null ? null : (FileDescriptor) socketImplFdField.get(impl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String reason(int code) {
        if (code == 200) {
            return "OK";
        }
        if (code == 400) {
            return "Bad Request";
        }
        if (code == 403) {
            return "Forbidden";
        }
        if (code == 404) {
            return "Not Found";
        }
        if (code == 413) {
            return "Payload Too Large";
        }
        if (code == 500) {
            return "Internal Server Error";
        }
        return "Error";
    }

    public String getIp() {
        return localIp();
    }

    /** 本机局域网 IPv4（没有则回退 127.0.0.1），供设置页与网页展示。 */
    public static String localIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : nif.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (ip instanceof Inet4Address && !ip.isLoopbackAddress()) {
                        if (ip.isSiteLocalAddress()) {
                            return ip.getHostAddress();
                        }
                        if (fallback == null) {
                            fallback = ip.getHostAddress();
                        }
                    }
                }
            }
            return fallback == null ? "127.0.0.1" : fallback;
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public String getUrl() {
        return "http://" + getIp() + ":" + getPort();
    }

    private String buildStatusJson() {
        File ext = QuestionImporter.externalFile(context);
        boolean imported = ext != null && ext.isFile();
        int count = QuestionBank.load(context).size();
        return "{\"running\":" + running + ",\"port\":" + port + ",\"count\":" + count
                + ",\"imported\":" + imported + "}";
    }

    // ---------------- 手机填 Key（AI 解析设置） ----------------

    private void handleAiSave(byte[] body, OutputStream out) throws IOException {
        try {
            JSONObject input = new JSONObject(new String(body, "UTF-8"));
            String llm = input.optString("llm", "").trim();
            String tts = input.optString("tts", "").trim();
            if (!llm.isEmpty()) {
                ApiKeys.saveLlmKey(context, llm);
            }
            if (!tts.isEmpty()) {
                ApiKeys.saveTtsKey(context, tts);
            }
            // 换了 Key，旧的测试结论作废。
            Prefs.get(context).setAiTest("llm", "");
            Prefs.get(context).setAiTest("tts", "");
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("message", "已加密保存到本机（Keystore）");
            fillAiStatus(result);
            respond(out, 200, "application/json", result.toString());
        } catch (Throwable t) {
            respond(out, 400, "application/json",
                    "{\"ok\":false,\"message\":\"请求格式错误，请刷新页面重试\"}");
        }
    }

    private void handleAiTest(byte[] body, OutputStream out) throws IOException {
        String target = "llm";
        try {
            target = new JSONObject(new String(body, "UTF-8")).optString("target", "llm");
        } catch (Throwable ignored) {
            // 默认测试 Agnes
        }
        boolean tts = "tts".equals(target);
        AiTestResult result = tts ? testTts() : testLlm();
        Prefs.get(context).setAiTest(tts ? "tts" : "llm", result.summary);
        try {
            JSONObject json = result.toJson();
            fillAiStatus(json);
            respond(out, 200, "application/json", json.toString());
        } catch (Throwable t) {
            respond(out, 200, "application/json", "{\"ok\":false,\"message\":\"测试失败\"}");
        }
    }

    private void fillAiStatus(JSONObject json) throws org.json.JSONException {
        Prefs prefs = Prefs.get(context);
        String llm = ApiKeys.getLlmKey(context);
        String tts = ApiKeys.getTtsKey(context);
        json.put("llm", !TextUtils.isEmpty(llm));
        json.put("llm_mask", ApiKeys.mask(llm));
        json.put("llm_source", ApiKeys.isLlmBuiltin(context) ? "builtin" : "user");
        json.put("tts", !TextUtils.isEmpty(tts));
        json.put("tts_mask", ApiKeys.mask(tts));
        json.put("tts_source", ApiKeys.isTtsBuiltin(context) ? "builtin" : "user");
        json.put("last_llm", prefs.getAiTest("llm"));
        json.put("last_tts", prefs.getAiTest("tts"));
        json.put("pin_set", prefs.hasPin());
    }

    private String buildAiStatusJson() {
        try {
            JSONObject json = new JSONObject();
            fillAiStatus(json);
            return json.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    private AiTestResult testLlm() {
        String key = ApiKeys.getLlmKey(context);
        if (TextUtils.isEmpty(key)) {
            return new AiTestResult(false, "未配置 Agnes Key", 0L);
        }
        long start = System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "https://api.agnes-ai.cn/v1/chat/completions").openConnection();
            connection.setConnectTimeout(5000);
            // 免费档延迟实测 22~41 秒，测试连接放宽到 95 秒。
            connection.setReadTimeout(95000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Accept", "application/json");

            JSONObject body = new JSONObject();
            body.put("model", "agnes-3.0-flash");
            JSONArray messages = new JSONArray();
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", "你是小学作业教练，只给启发式提示、绝不给出答案。"
                    + "只输出一个 JSON 对象，字段 hint 为一句 20~40 字简体中文思路或鼓励。");
            messages.put(system);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", "这是连接测试。题目：3 + 5 = ?。只输出 JSON，不要给答案。");
            messages.put(user);
            body.put("messages", messages);
            JSONObject format = new JSONObject();
            format.put("type", "json_object");
            body.put("response_format", format);

            writeJson(connection, body);
            int code = connection.getResponseCode();
            long ms = System.currentTimeMillis() - start;
            if (code != 200) {
                String error = readStreamQuietly(connection.getErrorStream(), 300);
                return new AiTestResult(false,
                        "HTTP " + code + errorSuffix(error.replace(key, "sk-****")), ms);
            }
            String text = readStreamQuietly(connection.getInputStream(), 8000);
            JSONObject root = new JSONObject(text);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return new AiTestResult(false, "HTTP 200，但返回里没有 choices", ms);
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            String content = message == null ? "" : message.optString("content", "");
            JSONObject parsed = new JSONObject(content);
            if (!containsChinese(content)) {
                return new AiTestResult(false, "JSON 可解析，但返回内容不是中文", ms);
            }
            return new AiTestResult(true, "Agnes 200 · " + ms + "ms · JSON 可解析："
                    + clip(parsed.toString(), 40), ms);
        } catch (Throwable t) {
            long ms = System.currentTimeMillis() - start;
            return new AiTestResult(false, "请求失败：" + describe(t), ms);
        } finally {
            disconnectQuietly(connection);
        }
    }

    private AiTestResult testTts() {
        String key = ApiKeys.getTtsKey(context);
        if (TextUtils.isEmpty(key)) {
            return new AiTestResult(false, "未配置 MiMo Key", 0L);
        }
        long start = System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(
                    "https://api.xiaomimimo.com/v1/chat/completions").openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(12000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Accept", "application/json");

            JSONObject body = new JSONObject();
            body.put("model", "mimo-v2.5-tts");
            JSONArray messages = new JSONArray();
            JSONObject instruction = new JSONObject();
            instruction.put("role", "user");
            instruction.put("content", "温柔、鼓励，语速稍慢，像老师在旁边轻声提示");
            messages.put(instruction);
            JSONObject assistant = new JSONObject();
            assistant.put("role", "assistant");
            assistant.put("content", "我们一起再想一想。");
            messages.put(assistant);
            body.put("messages", messages);
            JSONObject audio = new JSONObject();
            audio.put("format", "mp3");
            audio.put("voice", "冰糖");
            body.put("audio", audio);

            writeJson(connection, body);
            int code = connection.getResponseCode();
            long ms = System.currentTimeMillis() - start;
            if (code != 200) {
                String error = readStreamQuietly(connection.getErrorStream(), 300);
                return new AiTestResult(false,
                        "HTTP " + code + errorSuffix(error.replace(key, "sk-****")), ms);
            }
            String text = readStreamQuietly(connection.getInputStream(), 60000);
            JSONObject root = new JSONObject(text);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return new AiTestResult(false, "HTTP 200，但返回里没有 choices", ms);
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            JSONObject audioObj = message == null ? null : message.optJSONObject("audio");
            String data = audioObj == null ? "" : audioObj.optString("data", "");
            if (TextUtils.isEmpty(data)) {
                return new AiTestResult(false, "HTTP 200，但没有返回音频数据", ms);
            }
            byte[] mp3 = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
            if (mp3 == null || mp3.length == 0) {
                return new AiTestResult(false, "音频数据解码失败", ms);
            }
            return new AiTestResult(true, "MiMo 200 · " + ms + "ms · 音频 "
                    + mp3.length + " 字节", ms);
        } catch (Throwable t) {
            long ms = System.currentTimeMillis() - start;
            return new AiTestResult(false, "请求失败：" + describe(t), ms);
        } finally {
            disconnectQuietly(connection);
        }
    }

    private static void writeJson(HttpURLConnection connection, JSONObject body) throws IOException {
        OutputStream out = connection.getOutputStream();
        try {
            out.write(body.toString().getBytes("UTF-8"));
            out.flush();
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    private static void disconnectQuietly(HttpURLConnection connection) {
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static String readStreamQuietly(InputStream in, int limit) {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            while (buffer.size() < limit) {
                int read = in.read(chunk);
                if (read < 0) {
                    break;
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }

    private static String errorSuffix(String error) {
        if (TextUtils.isEmpty(error)) {
            return "";
        }
        return " · " + clip(error.replaceAll("\\s+", " ").trim(), 120);
    }

    private static boolean containsChinese(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String describe(Throwable t) {
        String name = t.getClass().getSimpleName();
        String message = t.getMessage();
        return message == null || message.isEmpty() ? name : name + " " + clip(message, 80);
    }

    private static final class AiTestResult {
        final boolean ok;
        final String message;
        final long elapsedMs;
        final String summary;

        AiTestResult(boolean ok, String message, long elapsedMs) {
            this.ok = ok;
            this.message = message;
            this.elapsedMs = elapsedMs;
            this.summary = (ok ? "成功" : "失败") + " · "
                    + new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
                    .format(new java.util.Date()) + " · " + clip(message, 60);
        }

        JSONObject toJson() throws org.json.JSONException {
            JSONObject json = new JSONObject();
            json.put("ok", ok);
            json.put("message", message);
            json.put("elapsed_ms", elapsedMs);
            return json;
        }
    }

    private String buildAiPage() {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=zh-CN><head><meta charset=utf-8>")
                .append("<meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>AI 解析设置</title><style>")
                .append("body{margin:0;padding:22px;background:#0e1218;color:#f2f6fa;font-family:-apple-system,'Segoe UI',sans-serif}")
                .append("h1{font-size:26px;margin:0 0 6px}.url{font-size:24px;font-weight:700;color:#4da3ff;word-break:break-all;margin:12px 0}")
                .append(".meta{color:#9fb0c0;font-size:15px;line-height:1.8;margin-bottom:16px}")
                .append(".card{background:#161e27;border:1px solid #2a3542;border-radius:14px;padding:18px}")
                .append("label{display:block;color:#9fb0c0;font-size:15px;margin:12px 0 6px}")
                .append("input{width:100%;box-sizing:border-box;background:#1b242e;color:#f2f6fa;border:1px solid #2a3542;border-radius:10px;padding:14px;font-size:18px}")
                .append("button{margin-top:14px;margin-right:10px;padding:14px 24px;font-size:17px;border-radius:10px;border:0;background:#2e7dff;color:#fff}")
                .append("button.ghost{background:#1b242e;border:1px solid #2a3542;color:#f2f6fa}")
                .append("button.warn{background:#a33}")
                .append("#state{margin-top:14px;font-size:16px;line-height:1.7;white-space:pre-wrap;color:#9fb0c0}")
                .append("#msg{margin-top:12px;white-space:pre-wrap;font-size:17px;line-height:1.7}")
                .append(".ok{color:#58d68d}.bad{color:#ff6b6b}")
                .append("</style></head><body>")
                .append("<h1>AI 解析与语音设置</h1>")
                .append("<div class=url>").append(escape(getUrl())).append("/ai</div>")
                .append("<div class=meta>自带内置默认 Key；这里填写会覆盖内置默认，清除后回落内置。"
                        + "网页不会回显完整 Key。此页面只在电视「AI 解析设置」页打开时可用，关闭页面即停服务。</div>")
                .append("<div class=card>")
                .append("<label>家长密码（4 位）</label>")
                .append("<input id=pin inputmode=numeric maxlength=4 placeholder='电视「家长设置」里设置的 PIN'>")
                .append("<label>Agnes 解析 Key</label>")
                .append("<input id=llm type=password autocomplete=off placeholder='粘贴 sk-… 开头的 Key；留空则不改'>")
                .append("<label>MiMo 语音 Key</label>")
                .append("<input id=tts type=password autocomplete=off placeholder='粘贴 MiMo Key；留空则不改'>")
                .append("<button onclick='save()'>保存</button>")
                .append("<button class=ghost onclick='testAll()'>测试连接</button>")
                .append("<button class=warn onclick='clearKeys()'>清除 Key</button>")
                .append("<div id=state>正在读取状态…</div>")
                .append("<div id=msg></div></div>")
                .append("<div class=meta>「测试连接」会真实请求 Agnes 与 MiMo（Agnes 约 15~60 秒，MiMo 约 1~5 秒）；"
                        + "失败只显示 HTTP 错误码与消息，不会显示 Key。</div>")
                .append("<script>")
                .append("function pin(){return document.getElementById('pin').value.trim();}")
                .append("function show(t,ok){var m=document.getElementById('msg');m.textContent=t;m.className=ok?'ok':'bad';}")
                .append("async function refresh(){try{var r=await fetch('/ai/status');var j=await r.json();")
                .append("var s='Agnes：'+(j.llm?((j.llm_source==='builtin'?'已内置 ':'已配置 ')+j.llm_mask):'未配置')+'\\nMiMo：'+(j.tts?((j.tts_source==='builtin'?'已内置 ':'已配置 ')+j.tts_mask):'未配置');")
                .append("if(j.last_llm){s+='\\nAgnes 最近测试：'+j.last_llm;}if(j.last_tts){s+='\\nMiMo 最近测试：'+j.last_tts;}")
                .append("document.getElementById('state').textContent=s;}catch(e){document.getElementById('state').textContent='状态读取失败：'+e;}}")
                .append("async function save(){var p=pin();if(!p){show('请先输入电视上的 4 位 PIN',false);return;}")
                .append("var llm=document.getElementById('llm').value.trim();var tts=document.getElementById('tts').value.trim();")
                .append("if(!llm&&!tts){show('两个都空着，没有需要保存的内容',false);return;}")
                .append("try{var r=await fetch('/ai/save',{method:'POST',headers:{'X-Pin':p,'Content-Type':'application/json'},body:JSON.stringify({llm:llm,tts:tts})});")
                .append("var j=await r.json();show(j.message,r.ok&&j.ok);")
                .append("document.getElementById('llm').value='';document.getElementById('tts').value='';refresh();}")
                .append("catch(e){show('请求失败：'+e,false);}}")
                .append("async function testAll(){var p=pin();if(!p){show('请先输入电视上的 4 位 PIN',false);return;}")
                .append("var out='',okAll=true;show('正在测试 Agnes…',true);")
                .append("try{var r=await fetch('/ai/test',{method:'POST',headers:{'X-Pin':p,'Content-Type':'application/json'},body:'{\"target\":\"llm\"}'});")
                .append("var j=await r.json();out+='Agnes：'+(j.message||'失败')+'\\n';okAll=okAll&&!!j.ok;}")
                .append("catch(e){out+='Agnes：请求失败 '+e+'\\n';okAll=false;}")
                .append("show(out+'正在测试 MiMo…',true);")
                .append("try{var r2=await fetch('/ai/test',{method:'POST',headers:{'X-Pin':p,'Content-Type':'application/json'},body:'{\"target\":\"tts\"}'});")
                .append("var j2=await r2.json();out+='MiMo：'+(j2.message||'失败');okAll=okAll&&!!j2.ok;}")
                .append("catch(e2){out+='MiMo：请求失败 '+e2;okAll=false;}")
                .append("show(out,okAll);refresh();}")
                .append("async function clearKeys(){var p=pin();if(!p){show('请先输入电视上的 4 位 PIN',false);return;}")
                .append("if(!confirm('确定清除电视上保存的两个 Key？')){return;}")
                .append("try{var r=await fetch('/ai/clear',{method:'POST',headers:{'X-Pin':p}});var j=await r.json();show(j.message,r.ok&&j.ok);refresh();}")
                .append("catch(e){show('请求失败：'+e,false);}}")
                .append("refresh();")
                .append("</script></body></html>");
        return html.toString();
    }

    /** v3.1：网页版家长控制台单页（assets/web/index.html），只读一次并缓存。 */
    private volatile String adminHtml;

    private String buildAdminPage() {
        String cached = adminHtml;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (adminHtml == null) {
                try {
                    adminHtml = readAsset("web/index.html");
                } catch (Throwable t) {
                    adminHtml = "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                            + "<title>家长控制台</title></head><body style=\"margin:0;padding:24px;"
                            + "background:#0e1218;color:#f2f6fa;font-family:sans-serif\">"
                            + "<h1>页面资源缺失</h1><p>请重新安装应用后再试。</p></body></html>";
                }
            }
            return adminHtml;
        }
    }

    /** v3.3：模板/提示词文本只在被点击时才加载，这里缓存一次，避免重复读 assets。 */
    private final Map<String, String> textAssetCache = new HashMap<String, String>();

    private String readAssetCached(String name) throws IOException {
        String cached = textAssetCache.get(name);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = textAssetCache.get(name);
            if (cached == null) {
                cached = readAsset(name).trim();
                textAssetCache.put(name, cached);
            }
            return cached;
        }
    }

    private String readAsset(String name) throws IOException {
        InputStream in;
        try {
            in = context.getAssets().open(name);
        } catch (IOException first) {
            // aapt2 在 Windows 上会把 assets 子目录写成反斜杠（assets/web\index.html），
            // AssetManager 里就成了一个带反斜杠的平铺文件名，这里兼容两种写法。
            if (name.indexOf('/') < 0) {
                throw first;
            }
            in = context.getAssets().open(name.replace('/', '\\'));
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] bytes = new byte[8192];
            int read;
            while ((read = in.read(bytes)) > 0) {
                buffer.write(bytes, 0, read);
            }
            return buffer.toString("UTF-8");
        } finally {
            in.close();
        }
    }

    private String buildPage() {
        File ext = QuestionImporter.externalFile(context);
        boolean imported = ext != null && ext.isFile();
        int count = QuestionBank.load(context).size();
        String version = "";
        if (imported) {
            try {
                String text = QuestionImporter.readText(ext);
                if (text.trim().startsWith("{")) {
                    version = new org.json.JSONObject(text).optString("version", "");
                }
            } catch (Exception ignored) {
                // 版本只是展示信息
            }
        }
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=zh-CN><head><meta charset=utf-8>")
                .append("<meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<title>题库更新</title><style>")
                .append("body{margin:0;padding:24px;background:#0e1218;color:#f2f6fa;font-family:-apple-system,'Segoe UI',sans-serif}")
                .append("h1{font-size:26px;margin:0 0 6px}.url{font-size:24px;font-weight:700;color:#4da3ff;word-break:break-all;margin:12px 0}")
                .append(".meta{color:#9fb0c0;font-size:15px;line-height:1.8;margin-bottom:18px}")
                .append(".card{background:#161e27;border:1px solid #2a3542;border-radius:14px;padding:18px;margin-bottom:16px}")
                .append("label{display:block;color:#9fb0c0;font-size:14px;margin:10px 0 6px}")
                .append("input,textarea{width:100%;box-sizing:border-box;background:#1b242e;color:#f2f6fa;border:1px solid #2a3542;border-radius:10px;padding:12px;font-size:16px}")
                .append("textarea{min-height:180px;font-family:monospace;font-size:13px}")
                .append("button{margin-top:12px;margin-right:10px;padding:12px 22px;font-size:16px;border-radius:10px;border:0;background:#2e7dff;color:#fff}")
                .append("button.ghost{background:#1b242e;border:1px solid #2a3542;color:#f2f6fa}")
                .append("#msg{margin-top:14px;white-space:pre-wrap;font-size:16px;line-height:1.6}")
                .append(".ok{color:#58d68d}.bad{color:#ff6b6b}")
                .append("</style></head><body>")
                .append("<h1>题库局域网更新</h1>")
                .append("<div class=url>").append(escape(getUrl())).append("</div>")
                .append("<div class=meta>电视当前题库：")
                .append(imported ? "导入题库" : "内置题库").append(" · ").append(count).append(" 题")
                .append(version.isEmpty() ? "" : " · 版本 " + escape(version))
                .append("<br>手机与电视连同一个 Wi-Fi/热点，用浏览器打开上面的地址。此页面只在电视「题库管理」页打开时可用。</div>")
                .append("<div class=card><label>家长密码（4 位）</label>")
                .append("<input id=pin inputmode=numeric maxlength=4 placeholder='输入电视上设置的 PIN'>")
                .append("<label>选择题库 JSON 文件</label>")
                .append("<input id=file type=file accept='.json,application/json'>")
                .append("<label>或者直接粘贴 JSON</label>")
                .append("<textarea id=text placeholder='{\"version\":\"2\",\"questions\":[{\"q\":\"3+5=?\",\"options\":[\"7\",\"8\",\"9\"],\"answer\":1}]}'></textarea>")
                .append("<button onclick='upload()'>上传并校验</button>")
                .append("<button class=ghost onclick='rollback()'>回退到上一版</button>")
                .append("<div id=msg></div></div>")
                .append("<div class=meta>校验规则：questions 至少 5 题；每题需要题干、2~6 个选项和正确答案；可带 version/difficulty/category。")
                .append("上传前会自动把当前题库备份为 questions.bak。所有请求仅在本机局域网内处理。</div>")
                .append("<script>")
                .append("function show(t,ok){var m=document.getElementById('msg');m.textContent=t;m.className=ok?'ok':'bad';}")
                .append("async function upload(){")
                .append("var pin=document.getElementById('pin').value.trim();")
                .append("var f=document.getElementById('file').files[0];")
                .append("var t=document.getElementById('text').value;")
                .append("var data=t;")
                .append("if(f){data=await f.text();}")
                .append("if(!data){show('请选择文件或粘贴 JSON',false);return;}")
                .append("try{var r=await fetch('/upload',{method:'POST',headers:{'X-Pin':pin,'Content-Type':'application/json'},body:data});")
                .append("var s=await r.text();show(s,r.ok);if(r.ok){setTimeout(function(){location.reload();},1200);}}")
                .append("catch(e){show('请求失败：'+e,false);}}")
                .append("async function rollback(){")
                .append("var pin=document.getElementById('pin').value.trim();")
                .append("if(!confirm('确定回退到上一版题库？')){return;}")
                .append("try{var r=await fetch('/rollback',{method:'POST',headers:{'X-Pin':pin}});")
                .append("var s=await r.text();show(s,r.ok);if(r.ok){setTimeout(function(){location.reload();},1200);}}")
                .append("catch(e){show('请求失败：'+e,false);}}")
                .append("</script></body></html>");
        return html.toString();
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

