package com.family.quizlauncher;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * v3.1：网页版家长控制台的登录令牌与失败退避。
 *
 * 全部为内存态：进程重启即失效，不写任何磁盘/日志。
 * - 令牌 32 位随机 hex，30 分钟滑动过期（每次校验刷新），最多 5 个，按 FIFO 淘汰；
 * - 同一客户端（IP + User-Agent 粗略聚合）连续失败 5 次锁 60 秒，锁定期内不校验密码；
 * - 登录成功清零失败计数。
 */
public final class TokenAuth {

    private static final long TTL_MS = 30L * 60L * 1000L;
    private static final int MAX_TOKENS = 5;
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 60L * 1000L;
    /** 客户端表上限，防止有人用伪造 UA 撑爆内存。 */
    private static final int MAX_CLIENTS = 200;

    public static final int OK = 0;
    public static final int BAD_PIN = 1;
    public static final int LOCKED = 2;

    public static class Outcome {
        public int code;
        public String token;
        public int remaining;
        public int retryAfter;
    }

    private static class ClientState {
        int fails;
        long lockedUntil;
    }

    private static TokenAuth sInstance;

    private final Map<String, ClientState> clients = new HashMap<String, ClientState>();
    /** 按插入顺序淘汰，保证最多同时 5 个有效令牌（FIFO）。 */
    private final LinkedHashMap<String, Long> tokens = new LinkedHashMap<String, Long>();
    private final SecureRandom random = new SecureRandom();

    private TokenAuth() {
    }

    public static synchronized TokenAuth get() {
        if (sInstance == null) {
            sInstance = new TokenAuth();
        }
        return sInstance;
    }

    /** 校验并签发令牌。expectedPin 取自 Prefs，不在这里落盘或记录。 */
    public synchronized Outcome login(String clientKey, String pin, String expectedPin) {
        Outcome outcome = new Outcome();
        long now = System.currentTimeMillis();
        String key = clientKey == null ? "" : clientKey;
        ClientState state = clients.get(key);

        if (state != null && state.lockedUntil > now) {
            outcome.code = LOCKED;
            outcome.retryAfter = (int) Math.ceil((state.lockedUntil - now) / 1000.0);
            return outcome;
        }
        if (state != null && state.lockedUntil > 0) {
            // 锁定已过期：重新开始计数
            state.fails = 0;
            state.lockedUntil = 0;
        }

        if (pin == null || expectedPin == null || expectedPin.isEmpty() || !pin.equals(expectedPin)) {
            if (state == null) {
                if (clients.size() >= MAX_CLIENTS) {
                    evictOneClient();
                }
                state = new ClientState();
                clients.put(key, state);
            }
            state.fails++;
            if (state.fails >= MAX_FAILS) {
                state.lockedUntil = now + LOCK_MS;
                outcome.code = LOCKED;
                outcome.retryAfter = 60;
                return outcome;
            }
            outcome.code = BAD_PIN;
            outcome.remaining = MAX_FAILS - state.fails;
            return outcome;
        }

        if (state != null) {
            state.fails = 0;
            state.lockedUntil = 0;
        }
        outcome.code = OK;
        outcome.token = issueToken(now);
        return outcome;
    }

    /** 返回剩余有效秒数；无效/过期返回 -1。有效时顺延 30 分钟。 */
    public synchronized long sessionSeconds(String token) {
        if (token == null || token.isEmpty()) {
            return -1L;
        }
        long now = System.currentTimeMillis();
        Long expireAt = tokens.get(token);
        if (expireAt == null || expireAt <= now) {
            tokens.remove(token);
            return -1L;
        }
        // LinkedHashMap 对已存在的 key 重新 put 不会改变插入顺序，FIFO 语义保持不变。
        tokens.put(token, now + TTL_MS);
        return Math.max(1L, (now + TTL_MS - now) / 1000L);
    }

    public synchronized boolean isValid(String token) {
        return sessionSeconds(token) > 0;
    }

    public synchronized void logout(String token) {
        if (token != null && !token.isEmpty()) {
            tokens.remove(token);
        }
    }

    private String issueToken(long now) {
        pruneExpired(now);
        while (tokens.size() >= MAX_TOKENS) {
            Iterator<String> iterator = tokens.keySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
        String token;
        do {
            token = randomHex(32);
        } while (tokens.containsKey(token));
        tokens.put(token, now + TTL_MS);
        return token;
    }

    private void pruneExpired(long now) {
        Iterator<Map.Entry<String, Long>> iterator = tokens.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now) {
                iterator.remove();
            }
        }
    }

    private void evictOneClient() {
        Iterator<String> iterator = clients.keySet().iterator();
        if (iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    /** 16 字节随机数 → 32 位小写 hex。 */
    private String randomHex(int chars) {
        byte[] bytes = new byte[chars / 2];
        random.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(chars);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }
}
