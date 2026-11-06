package com.family.quizlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 所有本地配置的唯一入口。数据量很小，直接使用 SharedPreferences，避免引入数据库。
 */
public class Prefs {

    private static final String FILE_NAME = "quiz_home";
    private static final String KEY_INIT = "initialized";
    private static final String KEY_RESTRICTED = "restricted";
    private static final String KEY_MINUTES = "minutes";
    private static final String KEY_REQUIRED = "required";
    private static final String KEY_EXPIRE = "expire_at";
    private static final String KEY_PASS_PREFIX = "pass_until_";
    /** 放行时记录的开机会话标识（Settings.Global.BOOT_COUNT），重启后变化。 */
    private static final String KEY_PASS_BOOT_PREFIX = "pass_boot_";
    /** 放行时的 SystemClock.elapsedRealtime()，重启后归零。 */
    private static final String KEY_PASS_ELAPSED_PREFIX = "pass_elapsed_";
    private static final String KEY_PIN = "pin";
    private static final String KEY_RECORDS = "records";
    private static final String KEY_CUSTOM_QUESTIONS = "custom_questions";
    /** 遮罩期间把 STREAM_MUSIC 降到 0 之前保存的音量，进程异常退出后靠它恢复。 */
    private static final String KEY_AUDIO_BACKUP = "audio_backup_music_volume";
    /** v2.8：家长是否开启「局域网控制服务」（前台服务），进程被杀后据此恢复。 */
    private static final String KEY_CONTROL_ENABLED = "control_enabled";
    /** v2.9：掌握即移出的相关设置。 */
    private static final String KEY_MASTERED_THRESHOLD = "mastered_threshold";
    private static final String KEY_AUTO_REMOVE = "auto_remove";
    private static final String KEY_ENABLED_SUBJECTS = "enabled_subjects";
    /** v3.0：严格模式（只放行白名单）与白名单。 */
    private static final String KEY_STRICT_MODE = "strict_mode";
    private static final String KEY_ALLOW_LIST = "strict_allow_list";
    /** v3.0：默认名单迁移标记，保证 U 盘播放器只被自动补进一次。 */
    private static final String KEY_DEFAULTS_SEAL = "defaults_seal";
    private static final String SEAL_V3 = "v3";

    private static Prefs sInstance;

    private final Context appContext;
    private final SharedPreferences sp;

    private Prefs(Context context) {
        appContext = context.getApplicationContext();
        sp = appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized Prefs get(Context context) {
        if (sInstance == null) {
            sInstance = new Prefs(context.getApplicationContext());
        }
        return sInstance;
    }

    /** 首次启动时写入默认受限应用；之后再也不会覆盖家长的修改。 */
    public void initRestricted(Set<String> defaults) {
        if (!sp.getBoolean(KEY_INIT, false)) {
            sp.edit().putBoolean(KEY_INIT, true)
                    .putStringSet(KEY_RESTRICTED, new HashSet<String>(defaults))
                    .apply();
        }
    }

    /**
     * v3.0：升级迁移。老版本已经 initialized，initRestricted 不会再覆盖，
     * 所以这里把「v3.0 新增的默认受限包」（U 盘播放器等）并进现有名单，只做一次。
     * 不碰家长手动改动过的 v2.9 老名单。
     */
    public void ensureV30Defaults(Context context) {
        if (SEAL_V3.equals(sp.getString(KEY_DEFAULTS_SEAL, ""))) {
            return;
        }
        Set<String> set = getRestricted();
        try {
            set.addAll(AppRepository.v30AddedDefaults(context));
        } catch (Throwable ignored) {
            set.add("com.tpv.xmic.contentbrowser.ebony2k15");
            set.add("org.droidtv.usbautoplayer");
        }
        sp.edit().putStringSet(KEY_RESTRICTED, set)
                .putString(KEY_DEFAULTS_SEAL, SEAL_V3)
                .apply();
    }

    public Set<String> getRestricted() {
        Set<String> saved = sp.getStringSet(KEY_RESTRICTED, null);
        return saved == null ? new HashSet<String>() : new HashSet<String>(saved);
    }

    public boolean isRestricted(String packageName) {
        return getRestricted().contains(packageName);
    }

    public void setRestricted(String packageName, boolean restricted) {
        Set<String> set = getRestricted();
        if (restricted) {
            set.add(packageName);
        } else {
            set.remove(packageName);
        }
        sp.edit().putStringSet(KEY_RESTRICTED, set).apply();
    }

    public void replaceRestricted(Set<String> packages) {
        sp.edit().putStringSet(KEY_RESTRICTED, new HashSet<String>(packages)).apply();
    }

    public int getMinutes() {
        return clamp(sp.getInt(KEY_MINUTES, 30), 1, 120);
    }

    public void setMinutes(int minutes) {
        sp.edit().putInt(KEY_MINUTES, clamp(minutes, 1, 120)).apply();
    }

    public int getRequired() {
        return clamp(sp.getInt(KEY_REQUIRED, 4), 1, 5);
    }

    public void setRequired(int required) {
        sp.edit().putInt(KEY_REQUIRED, clamp(required, 1, 5)).apply();
    }

    public boolean isPassActive() {
        return System.currentTimeMillis() < sp.getLong(KEY_EXPIRE, 0L);
    }

    public long getRemainMs() {
        return Math.max(0L, sp.getLong(KEY_EXPIRE, 0L) - System.currentTimeMillis());
    }

    public long getExpireAt() {
        return sp.getLong(KEY_EXPIRE, 0L);
    }

    public void grantPass() {
        long expireAt = System.currentTimeMillis() + getMinutes() * 60L * 1000L;
        sp.edit().putLong(KEY_EXPIRE, expireAt).apply();
    }

    public void clearPass() {
        sp.edit().putLong(KEY_EXPIRE, 0L).apply();
    }

    /**
     * v2 拦截模式：每个受限应用单独记放行到期时间。
     * 不用全局放行，避免「答了一次所有 App 都能看」。
     * v2.1：放行只在本开机会话内有效——同时记录开机计数与 elapsedRealtime 基线。
     */
    public void grantPass(String packageName) {
        grantPass(packageName, getMinutes());
    }

    /**
     * v2.8：按指定分钟数放行（手机端可临时改时长，不改动家长设置里的 minutes）。
     * 仍然记录开机会话烙印，重启后作废。
     */
    public void grantPass(String packageName, int minutes) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        int value = clamp(minutes, 1, 120);
        long expireAt = System.currentTimeMillis() + value * 60L * 1000L;
        sp.edit()
                .putLong(KEY_PASS_PREFIX + packageName, expireAt)
                .putInt(KEY_PASS_BOOT_PREFIX + packageName, bootCount())
                .putLong(KEY_PASS_ELAPSED_PREFIX + packageName, SystemClock.elapsedRealtime())
                .apply();
    }

    public boolean isPassActive(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        if (System.currentTimeMillis() >= sp.getLong(KEY_PASS_PREFIX + packageName, 0L)) {
            return false;
        }
        if (isFromPreviousBoot(packageName)) {
            // 设备已经重启过：放行作废，顺手把全部放行记录清干净。
            clearAllPasses();
            return false;
        }
        return true;
    }

    /**
     * 主判断：这个放行记录是不是上一个开机会话留下的。
     *
     * 1) 开机计数（Settings.Global.BOOT_COUNT）变化 → 一定重启过；
     * 2) elapsedRealtime 比记录时还小 → 计时器归零过，一定重启过。
     *
     * 待机休眠不会让 elapsedRealtime 归零，所以「待机 30 分钟回来」仍算同一会话。
     */
    private boolean isFromPreviousBoot(String packageName) {
        int bootNow = bootCount();
        int bootStored = sp.getInt(KEY_PASS_BOOT_PREFIX + packageName, -1);
        if (bootNow >= 0 && bootStored >= 0 && bootNow != bootStored) {
            return true;
        }
        long elapsedStored = sp.getLong(KEY_PASS_ELAPSED_PREFIX + packageName, -1L);
        return elapsedStored > 0L && SystemClock.elapsedRealtime() < elapsedStored;
    }

    /** 当前开机计数；取不到时返回 -1，由 elapsedRealtime 兜底判断。 */
    private int bootCount() {
        try {
            return Settings.Global.getInt(appContext.getContentResolver(),
                    Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 清空所有 `pass_until_*` / `pass_boot_*` / `pass_elapsed_*`，含旧版本留下的全局放行。 */
    public void clearAllPasses() {
        SharedPreferences.Editor editor = sp.edit();
        for (String key : sp.getAll().keySet()) {
            if (key.startsWith(KEY_PASS_PREFIX)
                    || key.startsWith(KEY_PASS_BOOT_PREFIX)
                    || key.startsWith(KEY_PASS_ELAPSED_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    /**
     * 服务连接时补偿清理：设备重启过就一次性清空所有放行记录。
     * BOOT_COMPLETED 广播只是第二道保险（部分固件会拦截自启动），这里才是主逻辑。
     */
    public void purgeStalePasses() {
        for (String key : new HashSet<String>(sp.getAll().keySet())) {
            if (key.startsWith(KEY_PASS_PREFIX)) {
                isPassActive(key.substring(KEY_PASS_PREFIX.length()));
            }
        }
    }

    public long getRemainMs(String packageName) {
        if (!isPassActive(packageName)) {
            return 0L;
        }
        return Math.max(0L, sp.getLong(KEY_PASS_PREFIX + packageName, 0L)
                - System.currentTimeMillis());
    }

    public long getExpireAt(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return 0L;
        }
        return sp.getLong(KEY_PASS_PREFIX + packageName, 0L);
    }

    public void clearPass(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        sp.edit().remove(KEY_PASS_PREFIX + packageName)
                .remove(KEY_PASS_BOOT_PREFIX + packageName)
                .remove(KEY_PASS_ELAPSED_PREFIX + packageName)
                .apply();
    }

    public String getPin() {
        return sp.getString(KEY_PIN, "");
    }

    public boolean hasPin() {
        String pin = getPin();
        return pin != null && pin.length() == 4;
    }

    public void setPin(String pin) {
        sp.edit().putString(KEY_PIN, pin == null ? "" : pin).apply();
    }

    public String getIgnoredImportPath() {
        return sp.getString("ignored_import_path", "");
    }

    public long getIgnoredImportMtime() {
        return sp.getLong("ignored_import_mtime", -1L);
    }

    public void setIgnoredImport(String path, long mtime) {
        sp.edit().putString("ignored_import_path", path == null ? "" : path)
                .putLong("ignored_import_mtime", mtime).apply();
    }

    public String getCustomQuestions() {
        return sp.getString(KEY_CUSTOM_QUESTIONS, "");
    }

    public void setCustomQuestions(String json) {
        sp.edit().putString(KEY_CUSTOM_QUESTIONS, json == null ? "" : json).apply();
    }

    /** 静音前的音乐音量；-1 表示没有待恢复的记录。 */
    public int getAudioBackupVolume() {
        return sp.getInt(KEY_AUDIO_BACKUP, -1);
    }

    public void setAudioBackupVolume(int volume) {
        sp.edit().putInt(KEY_AUDIO_BACKUP, volume).apply();
    }

    public void clearAudioBackupVolume() {
        sp.edit().remove(KEY_AUDIO_BACKUP).apply();
    }

    /** 最近一次 AI 连接测试结果（只存中文摘要，绝不含 Key）。 */
    public String getAiTest(String which) {
        return sp.getString("ai_test_" + which, "");
    }

    public void setAiTest(String which, String summary) {
        sp.edit().putString("ai_test_" + which, summary == null ? "" : summary).apply();
    }

    public void addRecord(JSONObject record) {
        JSONArray old = getRecords();
        JSONArray array = new JSONArray();
        array.put(record);
        int keep = Math.min(old.length(), 59);
        for (int i = 0; i < keep; i++) {
            try {
                array.put(old.get(i));
            } catch (JSONException ignored) {
                break;
            }
        }
        sp.edit().putString(KEY_RECORDS, array.toString()).apply();
    }

    public JSONArray getRecords() {
        try {
            return new JSONArray(sp.getString(KEY_RECORDS, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    /** v2.8：手机端清空答题记录。 */
    public void clearRecords() {
        sp.edit().remove(KEY_RECORDS).apply();
    }

    /**
     * v2.8：列出仍然有效的放行（剔除重启前、已到期、时间倒流的记录）。
     * 返回按包名排序的 map，value 为剩余毫秒。
     */
    public Map<String, Long> listActivePasses() {
        TreeMap<String, Long> result = new TreeMap<String, Long>();
        for (String key : new HashSet<String>(sp.getAll().keySet())) {
            if (!key.startsWith(KEY_PASS_PREFIX)) {
                continue;
            }
            String pkg = key.substring(KEY_PASS_PREFIX.length());
            if (pkg.isEmpty() || !isPassActive(pkg)) {
                continue;
            }
            result.put(pkg, getRemainMs(pkg));
        }
        return result;
    }

    /** v2.9：掌握阈值（答对多少次算掌握），1~10，默认 3。 */
    public int getMasteredThreshold() {
        return clamp(sp.getInt(KEY_MASTERED_THRESHOLD, 3), 1, 10);
    }

    public void setMasteredThreshold(int threshold) {
        sp.edit().putInt(KEY_MASTERED_THRESHOLD, clamp(threshold, 1, 10)).apply();
    }

    /** v2.9：达到掌握阈值后是否自动从当前题库移出（默认关闭）。 */
    public boolean isAutoRemove() {
        return sp.getBoolean(KEY_AUTO_REMOVE, false);
    }

    public void setAutoRemove(boolean autoRemove) {
        sp.edit().putBoolean(KEY_AUTO_REMOVE, autoRemove).apply();
    }

    /** v2.9：启用的学科 key；空集合 = 全部启用。 */
    public Set<String> getEnabledSubjects() {
        Set<String> saved = sp.getStringSet(KEY_ENABLED_SUBJECTS, null);
        return saved == null ? new HashSet<String>() : new HashSet<String>(saved);
    }

    public void setEnabledSubjects(Set<String> keys) {
        sp.edit().putStringSet(KEY_ENABLED_SUBJECTS,
                new HashSet<String>(keys == null ? new HashSet<String>() : keys)).apply();
    }

    public boolean isSubjectEnabled(String key) {
        Set<String> enabled = getEnabledSubjects();
        return enabled.isEmpty() || (key != null && enabled.contains(key));
    }

    /** v3.0：严格模式。开启后任何不在白名单的前台应用都要答题（不再看受限名单）。 */
    public boolean isStrictMode() {
        return sp.getBoolean(KEY_STRICT_MODE, false);
    }

    public void setStrictMode(boolean enabled) {
        sp.edit().putBoolean(KEY_STRICT_MODE, enabled).apply();
    }

    /** v3.0：严格模式白名单；没有存过就用内置默认（含桌面/设置/自身/Provider/输入法）。 */
    public Set<String> getAllowList() {
        Set<String> saved = sp.getStringSet(KEY_ALLOW_LIST, null);
        if (saved == null || saved.isEmpty()) {
            return AppRepository.defaultAllowList();
        }
        return new HashSet<String>(saved);
    }

    /** 写入白名单。空集合会回落到默认值，避免「一个都不放行」把电视锁死。 */
    public void setAllowList(Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            packages = AppRepository.defaultAllowList();
        }
        sp.edit().putStringSet(KEY_ALLOW_LIST, new HashSet<String>(packages)).apply();
    }

    public void resetAllowList() {
        sp.edit().remove(KEY_ALLOW_LIST).apply();
    }

    /** v2.8：局域网控制服务开关（前台服务被系统回收后由家长页据此恢复）。 */
    public boolean isControlEnabled() {
        return sp.getBoolean(KEY_CONTROL_ENABLED, false);
    }

    public void setControlEnabled(boolean enabled) {
        sp.edit().putBoolean(KEY_CONTROL_ENABLED, enabled).apply();
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }
}
