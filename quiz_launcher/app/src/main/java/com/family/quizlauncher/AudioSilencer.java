package com.family.quizlauncher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

/**
 * 答题遮罩出现时让下层视频安静下来，分两层：
 *
 * 1) 音频焦点：requestAudioFocus(AUDIOFOCUS_GAIN_TRANSIENT)，遵守 AudioFocus 规范
 *    的视频 App 会自动暂停/静音；
 * 2) 兜底静音：把 STREAM_MUSIC 降到 0（原音量先写进 Prefs，进程被杀也能恢复）。
 *
 * 退出时严格按「先恢复音量、再放弃焦点」的顺序还原；异常路径由 try/finally、
 * 服务重连时的 restoreIfNeeded() 和看门狗兜底。
 */
public final class AudioSilencer {

    private static final Object LOCK = new Object();

    private static boolean active;
    private static Context appContext;
    private static AudioManager audioManager;
    private static AudioFocusRequest focusRequest;
    private static boolean focusGranted;
    private static int savedVolume = -1;

    private AudioSilencer() {
    }

    /** 遮罩显示时调用。重复调用无副作用。 */
    public static void enter(Context context) {
        synchronized (LOCK) {
            if (active || context == null) {
                return;
            }
            appContext = context.getApplicationContext();
            Prefs prefs = Prefs.get(appContext);
            // 上次进程异常退出可能没恢复，先把备份的音量还回去，再记录本次的。
            int pending = prefs.getAudioBackupVolume();
            if (pending >= 0) {
                setVolumeSafe(pending);
                prefs.clearAudioBackupVolume();
            }
            audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return;
            }
            active = true;
            requestFocus();
            try {
                savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                prefs.setAudioBackupVolume(savedVolume);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            } catch (Throwable ignored) {
                // 部分定制 ROM 禁止第三方改音量：音频焦点仍然生效。
                savedVolume = -1;
            }
        }
    }

    /** 遮罩移除时调用；必须保证异常时也执行。 */
    public static void exit() {
        synchronized (LOCK) {
            if (!active) {
                return;
            }
            try {
                restoreVolumeLocked();
            } finally {
                abandonFocusLocked();
                active = false;
            }
        }
    }

    /** 服务重连时补偿：如果上次静音后进程被杀，这里把音量恢复。 */
    public static void restoreIfNeeded(Context context) {
        synchronized (LOCK) {
            if (active || context == null) {
                return;
            }
            Prefs prefs = Prefs.get(context.getApplicationContext());
            int pending = prefs.getAudioBackupVolume();
            if (pending < 0) {
                return;
            }
            audioManager = (AudioManager) context.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);
            setVolumeSafe(pending);
            prefs.clearAudioBackupVolume();
        }
    }

    /** 朗读提示期间临时把音量还给用户，否则自己的 TTS 也会被静音。 */
    public static void allowSpeech() {
        synchronized (LOCK) {
            if (!active || audioManager == null || savedVolume <= 0) {
                return;
            }
            setVolumeSafe(savedVolume);
        }
    }

    /** 朗读结束（含失败/取消）后重新静音。 */
    public static void reSilence() {
        synchronized (LOCK) {
            if (!active || audioManager == null) {
                return;
            }
            setVolumeSafe(0);
        }
    }

    public static boolean isActive() {
        synchronized (LOCK) {
            return active;
        }
    }

    private static void requestFocus() {
        if (audioManager == null) {
            return;
        }
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                        @Override
                        public void onAudioFocusChange(int focusChange) {
                            // 遮罩期间不需要响应焦点变化，保持静音即可。
                        }
                    })
                    .build();
            focusGranted = audioManager.requestAudioFocus(focusRequest)
                    == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Throwable ignored) {
            focusGranted = false;
        }
    }

    private static void abandonFocusLocked() {
        if (audioManager == null || focusRequest == null) {
            return;
        }
        try {
            audioManager.abandonAudioFocusRequest(focusRequest);
        } catch (Throwable ignored) {
            // ignore
        }
        focusRequest = null;
        focusGranted = false;
    }

    private static void restoreVolumeLocked() {
        if (audioManager == null) {
            return;
        }
        try {
            Prefs prefs = Prefs.get(appContext);
            int backup = prefs.getAudioBackupVolume();
            if (backup >= 0) {
                setVolumeSafe(backup);
            } else if (savedVolume >= 0) {
                setVolumeSafe(savedVolume);
            }
            prefs.clearAudioBackupVolume();
        } catch (Throwable ignored) {
            // 恢复失败时备份仍然保留，下次服务重连会再恢复。
        } finally {
            savedVolume = -1;
        }
    }

    private static void setVolumeSafe(int volume) {
        if (audioManager == null) {
            return;
        }
        try {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int safe = volume < 0 ? 0 : Math.min(volume, max);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, safe, 0);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    /** 供报告/设置页显示实测结果。 */
    public static String describe() {
        synchronized (LOCK) {
            if (!active) {
                return "未生效";
            }
            return "音频焦点=" + (focusGranted ? "已获得" : "未获得")
                    + "，静音=" + (savedVolume >= 0 ? "已静音" : "失败");
        }
    }
}
