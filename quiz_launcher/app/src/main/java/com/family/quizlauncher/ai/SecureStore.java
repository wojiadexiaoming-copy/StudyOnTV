package com.family.quizlauncher.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.Charset;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 用 Android Keystore 里的不可导出密钥做 AES/GCM 加密，密文存 SharedPreferences。
 * Key 不进 APK、不进源码、不写日志；换机/清数据后需重新填写。
 */
public final class SecureStore {

    private static final String SP_NAME = "quiz_secrets";
    private static final String KEY_ALIAS = "quiz_secret_v1";
    private static final String SEP = ":";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private SecureStore() {
    }

    public static void save(Context context, String name, String value) {
        if (context == null || name == null) {
            return;
        }
        SharedPreferences sp = context.getApplicationContext()
                .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        if (value == null || value.trim().isEmpty()) {
            sp.edit().remove(name).apply();
            return;
        }
        String encrypted = encrypt(value.trim());
        if (encrypted == null) {
            // Keystore 不可用时宁可不保存，也不能把明文 Key 落盘。
            sp.edit().remove(name).apply();
            return;
        }
        sp.edit().putString(name, encrypted).apply();
    }

    public static String get(Context context, String name) {
        if (context == null || name == null) {
            return null;
        }
        SharedPreferences sp = context.getApplicationContext()
                .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        String stored = sp.getString(name, null);
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        String plain = decrypt(stored);
        return plain == null || plain.isEmpty() ? null : plain;
    }

    public static boolean has(Context context, String name) {
        String value = get(context, name);
        return value != null && !value.isEmpty();
    }

    public static void clear(Context context, String name) {
        if (context == null || name == null) {
            return;
        }
        context.getApplicationContext()
                .getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                .edit().remove(name).apply();
    }

    private static String encrypt(String plain) {
        try {
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] data = cipher.doFinal(plain.getBytes(UTF8));
            return Base64.encodeToString(iv, Base64.NO_WRAP) + SEP
                    + Base64.encodeToString(data, Base64.NO_WRAP);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String decrypt(String stored) {
        int split = stored.indexOf(SEP);
        if (split <= 0 || split >= stored.length() - 1) {
            return null;
        }
        try {
            byte[] iv = Base64.decode(stored.substring(0, split), Base64.NO_WRAP);
            byte[] data = Base64.decode(stored.substring(split + 1), Base64.NO_WRAP);
            SecretKey key = getOrCreateKey();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data), UTF8);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
