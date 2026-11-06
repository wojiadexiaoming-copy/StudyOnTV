package com.family.quizlauncher.ai;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Key 的唯一入口。
 *
 * 优先级：家长设置 / 手机网页填写的 Key（Keystore 加密）＞ 随 APK 内置的默认 Key
 * （自用授权，集中在 assets/ai_defaults.json）。清除用户 Key 后回落到内置默认。
 * 任何日志、UI、HTTP 响应都只允许出现掩码。
 */
public final class ApiKeys {

    private static final String NAME_LLM = "llm_key";
    private static final String NAME_TTS = "tts_key";
    private static final String DEFAULTS_ASSET = "ai_defaults.json";

    private static String sDefaultLlm;
    private static String sDefaultTts;
    private static boolean sDefaultsLoaded;

    private ApiKeys() {
    }

    private static synchronized void loadDefaults(Context context) {
        if (sDefaultsLoaded) {
            return;
        }
        sDefaultsLoaded = true;
        try {
            InputStream in = context.getAssets().open(DEFAULTS_ASSET);
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[1024];
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, read);
                }
                JSONObject json = new JSONObject(buffer.toString("UTF-8"));
                sDefaultLlm = json.optString("llm", "").trim();
                sDefaultTts = json.optString("tts", "").trim();
            } finally {
                in.close();
            }
        } catch (Throwable ignored) {
            // 没有内置文件时视为未内置
        }
    }

    /** 用户是否自己填过 Agnes Key（不含内置默认）。 */
    public static boolean hasUserLlmKey(Context context) {
        return SecureStore.has(context, NAME_LLM);
    }

    public static boolean hasUserTtsKey(Context context) {
        return SecureStore.has(context, NAME_TTS);
    }

    /** 当前是否有可用的 Agnes Key（用户 Key 或内置默认）。 */
    public static boolean hasLlmKey(Context context) {
        return !TextUtils.isEmpty(getLlmKey(context));
    }

    public static boolean hasTtsKey(Context context) {
        return !TextUtils.isEmpty(getTtsKey(context));
    }

    /** 当前 Key 是不是来自内置默认（用户没填过）。 */
    public static boolean isLlmBuiltin(Context context) {
        loadDefaults(context);
        return !hasUserLlmKey(context) && !TextUtils.isEmpty(sDefaultLlm);
    }

    public static boolean isTtsBuiltin(Context context) {
        loadDefaults(context);
        return !hasUserTtsKey(context) && !TextUtils.isEmpty(sDefaultTts);
    }

    /** 实际生效的 Agnes Key：用户 Key 优先，其次内置默认。 */
    public static String getLlmKey(Context context) {
        loadDefaults(context);
        String user = SecureStore.get(context, NAME_LLM);
        return TextUtils.isEmpty(user) ? sDefaultLlm : user;
    }

    public static String getTtsKey(Context context) {
        loadDefaults(context);
        String user = SecureStore.get(context, NAME_TTS);
        return TextUtils.isEmpty(user) ? sDefaultTts : user;
    }

    public static void saveLlmKey(Context context, String key) {
        SecureStore.save(context, NAME_LLM, key);
    }

    public static void saveTtsKey(Context context, String key) {
        SecureStore.save(context, NAME_TTS, key);
    }

    /** 只清用户 Key；内置默认仍然可用。 */
    public static void clearAll(Context context) {
        SecureStore.clear(context, NAME_LLM);
        SecureStore.clear(context, NAME_TTS);
    }

    /** 只显示前后各 4 位，网页/电视端都绝不回显完整 Key。 */
    public static String mask(String key) {
        if (key == null) {
            return "";
        }
        String value = key.trim();
        if (value.length() <= 8) {
            return value.isEmpty() ? "" : "…";
        }
        return value.substring(0, 4) + "…" + value.substring(value.length() - 4);
    }
}
