package com.family.quizlauncher.ai;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.TextUtils;
import android.util.Base64;

import com.family.quizlauncher.AudioSilencer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 语音朗读：MiMo TTS（在线 MP3，本地缓存）→ 系统中文 TTS → 静默（纯文字）。
 *
 * 网络合成在后台线程；播放用 MediaPlayer 的 accessibility 音频用途，避免被
 * AudioSilencer 把 STREAM_MUSIC 静音时连带静掉；系统 TTS 兜底时临时恢复音量。
 */
public final class SpeechRouter {

    public interface Listener {
        void onDone();

        void onError(String message);
    }

    private static final String ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions";
    private static final String MODEL = "mimo-v2.5-tts";
    private static final String VOICE = "冰糖";
    private static final int MAX_SPEECH_CHARS = 80;
    private static final int MAX_CACHE_FILES = 30;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
            new java.util.concurrent.ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "tts-client");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static MediaPlayer player;
    private static TextToSpeech tts;
    private static boolean ttsReady;
    private static Listener pendingListener;
    private static int token;
    private static String pendingUtteranceId;

    private SpeechRouter() {
    }

    public static void speakHint(Context context, String text, Listener listener) {
        final Context appContext = context.getApplicationContext();
        String clean = truncate(text);
        if (clean.isEmpty()) {
            if (listener != null) {
                listener.onError("没有可朗读的内容");
            }
            return;
        }
        final int requestToken;
        synchronized (SpeechRouter.class) {
            requestToken = ++token;
        }
        final String sourceText = clean;
        if (ApiKeys.hasTtsKey(appContext)) {
            final String key = ApiKeys.getTtsKey(appContext);
            EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    File file = cachedFile(appContext, sourceText);
                    if (file.isFile() && file.length() > 0) {
                        playFile(appContext, file, listener, requestToken);
                        return;
                    }
                    boolean ok = download(appContext, key, sourceText, file);
                    if (ok) {
                        playFile(appContext, file, listener, requestToken);
                    } else {
                        speakWithSystemTts(appContext, sourceText, listener, requestToken,
                                "在线语音不可用，已尝试系统语音");
                    }
                }
            });
        } else {
            speakWithSystemTts(appContext, clean, listener, requestToken,
                    "未配置语音 Key，已尝试系统语音");
        }
    }

    public static void stop() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                synchronized (SpeechRouter.class) {
                    token++;
                }
                stopPlayer();
                stopSystemTts();
            }
        });
    }

    public static void release() {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                synchronized (SpeechRouter.class) {
                    token++;
                    pendingListener = null;
                }
                stopPlayer();
                if (tts != null) {
                    try {
                        tts.stop();
                        tts.shutdown();
                    } catch (Throwable ignored) {
                        // ignore
                    }
                    tts = null;
                    ttsReady = false;
                }
                AudioSilencer.reSilence();
            }
        });
    }

    private static boolean download(Context context, String key, String text, File file) {
        long started = System.currentTimeMillis();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Accept", "application/json");

            JSONObject body = new JSONObject();
            body.put("model", MODEL);
            JSONArray messages = new JSONArray();
            JSONObject instruction = new JSONObject();
            instruction.put("role", "user");
            instruction.put("content", "温柔、鼓励，语速稍慢，像老师在旁边轻声提示");
            messages.put(instruction);
            JSONObject assistant = new JSONObject();
            assistant.put("role", "assistant");
            assistant.put("content", text);
            messages.put(assistant);
            body.put("messages", messages);
            JSONObject audio = new JSONObject();
            audio.put("format", "mp3");
            audio.put("voice", VOICE);
            body.put("audio", audio);

            OutputStream out = connection.getOutputStream();
            try {
                out.write(body.toString().getBytes("UTF-8"));
                out.flush();
            } finally {
                closeQuietly(out);
            }
            int code = connection.getResponseCode();
            if (code != 200) {
                android.util.Log.d("QuizGate", "mimo tts http=" + code
                        + " ms=" + (System.currentTimeMillis() - started));
                return false;
            }
            String response = readStream(connection.getInputStream());
            JSONObject root = new JSONObject(response);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return false;
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            JSONObject audioObj = message == null ? null : message.optJSONObject("audio");
            String data = audioObj == null ? null : audioObj.optString("data", "");
            if (TextUtils.isEmpty(data)) {
                return false;
            }
            byte[] mp3 = Base64.decode(data, Base64.DEFAULT);
            if (mp3.length == 0) {
                return false;
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            File temp = new File(file.getAbsolutePath() + ".tmp");
            FileOutputStream fos = new FileOutputStream(temp);
            try {
                fos.write(mp3);
                fos.flush();
            } finally {
                fos.close();
            }
            if (!temp.renameTo(file)) {
                temp.delete();
                return false;
            }
            pruneCache(parent);
            android.util.Log.d("QuizGate", "mimo tts bytes=" + mp3.length
                    + " ms=" + (System.currentTimeMillis() - started));
            return true;
        } catch (Throwable ignored) {
            android.util.Log.d("QuizGate", "mimo tts error=" + ignored.getClass().getSimpleName()
                    + " ms=" + (System.currentTimeMillis() - started));
            return false;
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

    private static void playFile(final Context context, final File file,
                                 final Listener listener, final int requestToken) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (!isCurrent(requestToken)) {
                    if (listener != null) {
                        listener.onError("已取消");
                    }
                    return;
                }
                stopPlayer();
                AudioSilencer.allowSpeech();
                try {
                    player = new MediaPlayer();
                    AudioAttributes attributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                    player.setAudioAttributes(attributes);
                    player.setDataSource(file.getAbsolutePath());
                    player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer mp) {
                            android.util.Log.d("QuizGate", "mimo tts play start");
                            mp.start();
                        }
                    });
                    player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        @Override
                        public void onCompletion(MediaPlayer mp) {
                            finishPlayback(listener);
                        }
                    });
                    player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override
                        public boolean onError(MediaPlayer mp, int what, int extra) {
                            finishPlaybackWithError(listener, "语音播放失败");
                            return true;
                        }
                    });
                    player.prepareAsync();
                } catch (Throwable t) {
                    finishPlaybackWithError(listener, "语音播放失败");
                }
            }
        });
    }

    private static void speakWithSystemTts(final Context context, final String text,
                                           final Listener listener, final int requestToken,
                                           final String failMessage) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                if (!isCurrent(requestToken)) {
                    if (listener != null) {
                        listener.onError("已取消");
                    }
                    return;
                }
                pendingListener = listener;
                pendingUtteranceId = "quiz-hint-" + requestToken;
                if (ttsReady && tts != null) {
                    speakNow(text);
                    return;
                }
                try {
                    tts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
                        @Override
                        public void onInit(int status) {
                            if (status != TextToSpeech.SUCCESS || tts == null) {
                                fail(listener, failMessage + "：系统语音初始化失败");
                                return;
                            }
                            ttsReady = true;
                            int available = tts.isLanguageAvailable(new Locale("zh", "CN"));
                            if (available < TextToSpeech.LANG_AVAILABLE) {
                                fail(listener, failMessage + "：本机没有中文语音包");
                                return;
                            }
                            tts.setLanguage(new Locale("zh", "CN"));
                            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                                @Override
                                public void onStart(String utteranceId) {
                                    // ignore
                                }

                                @Override
                                public void onDone(String utteranceId) {
                                    MAIN.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            AudioSilencer.reSilence();
                                            if (pendingListener != null) {
                                                pendingListener.onDone();
                                                pendingListener = null;
                                            }
                                        }
                                    });
                                }

                                @Override
                                public void onError(String utteranceId) {
                                    MAIN.post(new Runnable() {
                                        @Override
                                        public void run() {
                                            AudioSilencer.reSilence();
                                            if (pendingListener != null) {
                                                pendingListener.onError("系统语音播放失败");
                                                pendingListener = null;
                                            }
                                        }
                                    });
                                }
                            });
                            speakNow(text);
                        }
                    });
                } catch (Throwable t) {
                    fail(listener, failMessage + "：系统语音不可用");
                }
            }
        });
    }

    private static void speakNow(String text) {
        if (tts == null) {
            return;
        }
        AudioSilencer.allowSpeech();
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, pendingUtteranceId);
        } catch (Throwable t) {
            AudioSilencer.reSilence();
            if (pendingListener != null) {
                pendingListener.onError("系统语音播放失败");
                pendingListener = null;
            }
        }
    }

    private static void fail(Listener listener, String message) {
        AudioSilencer.reSilence();
        ttsReady = false;
        if (listener != null) {
            listener.onError(message);
        }
    }

    private static void finishPlayback(Listener listener) {
        stopPlayer();
        AudioSilencer.reSilence();
        if (listener != null) {
            listener.onDone();
        }
    }

    private static void finishPlaybackWithError(Listener listener, String message) {
        stopPlayer();
        AudioSilencer.reSilence();
        if (listener != null) {
            listener.onError(message);
        }
    }

    private static void stopPlayer() {
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                player.release();
            } catch (Throwable ignored) {
                // ignore
            }
            player = null;
        }
    }

    private static void stopSystemTts() {
        if (tts != null) {
            try {
                tts.stop();
            } catch (Throwable ignored) {
                // ignore
            }
        }
        pendingListener = null;
    }

    private static boolean isCurrent(int requestToken) {
        synchronized (SpeechRouter.class) {
            return token == requestToken;
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.trim().replaceAll("\\s+", " ");
        if (clean.length() > MAX_SPEECH_CHARS) {
            clean = clean.substring(0, MAX_SPEECH_CHARS);
        }
        return clean;
    }

    private static File cachedFile(Context context, String text) {
        File dir = new File(context.getCacheDir(), "tts");
        return new File(dir, sha1(text + "|" + VOICE) + ".mp3");
    }

    private static String sha1(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(text.hashCode());
        }
    }

    private static void pruneCache(File dir) {
        if (dir == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_CACHE_FILES) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.lastModified() < b.lastModified() ? -1
                        : (a.lastModified() == b.lastModified() ? 0 : 1);
            }
        });
        int removeCount = files.length - MAX_CACHE_FILES;
        for (int i = 0; i < removeCount; i++) {
            files[i].delete();
        }
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
