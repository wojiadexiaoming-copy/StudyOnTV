package com.family.quizlauncher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;

/**
 * 题库导入 / 校验 / 备份 / 回退。所有写入都在应用专属外部目录：
 * /sdcard/Android/data/<包名>/files/questions.json     当前题库
 * /sdcard/Android/data/<包名>/files/questions.bak      上一版题库
 * /sdcard/Android/data/<包名>/files/questions_import.json  adb push / 导入箱
 */
public class QuestionImporter {

    public static final int MAX_BYTES = 1024 * 1024;
    private static final int MIN_QUESTIONS = 5;
    private static final int MAX_QUESTIONS = 500;

    public static class Result {
        public final boolean ok;
        public final String message;
        public final int count;
        public final String version;
        /** v2.9：学科数量；新格式才有意义，旧格式固定 1。 */
        public final int subjects;
        /** v2.9：是否带 subjects 数组的新格式。 */
        public final boolean newFormat;

        public Result(boolean ok, String message, int count, String version) {
            this(ok, message, count, version, 1, false);
        }

        public Result(boolean ok, String message, int count, String version,
                      int subjects, boolean newFormat) {
            this.ok = ok;
            this.message = message;
            this.count = count;
            this.version = version;
            this.subjects = subjects;
            this.newFormat = newFormat;
        }
    }

    public static File externalFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, "questions.json");
    }

    public static File backupFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, "questions.bak");
    }

    public static File inboxFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        return dir == null ? null : new File(dir, "questions_import.json");
    }

    /**
     * 严格校验，返回中文错误信息。兼容两种格式：
     * - 新格式 {"version":3,"title","subjects":[{key,name,questions}]}
     * - 旧格式 {"version","title","questions"} 或纯数组（整体视为单学科，学科名取 title）
     * 错误信息精确到「第 X 学科第 Y 题」。
     */
    public static Result validate(String json) {
        if (json == null || json.trim().isEmpty()) {
            return fail("内容为空");
        }
        String trimmed = json.trim();
        if (trimmed.getBytes().length > MAX_BYTES) {
            return fail("文件超过 1MB 上限");
        }
        try {
            if (trimmed.startsWith("[")) {
                return validateOld(new JSONArray(trimmed), "", "");
            }
            JSONObject root = new JSONObject(trimmed);
            String version = root.optString("version", "");
            String title = root.optString("title", "").trim();
            JSONArray subjects = root.optJSONArray("subjects");
            if (subjects != null) {
                return validateNew(subjects, title, version);
            }
            JSONArray questions = root.optJSONArray("questions");
            if (questions == null) {
                return fail("JSON 里既没有 subjects 数组，也没有 questions 数组");
            }
            return validateOld(questions, title, version);
        } catch (JSONException e) {
            String detail = e.getMessage();
            return fail("JSON 格式错误" + (detail == null ? "" : "：" + detail));
        }
    }

    private static Result validateNew(JSONArray subjects, String title, String version) {
        if (subjects.length() == 0) {
            return fail("至少需要 1 个学科");
        }
        java.util.Map<String, Integer> usedKeys = new java.util.HashMap<String, Integer>();
        int total = 0;
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < subjects.length(); i++) {
            JSONObject subject = subjects.optJSONObject(i);
            if (subject == null) {
                return fail("第 " + (i + 1) + " 学科不是 JSON 对象");
            }
            String name = subject.optString("name", "").trim();
            String key = subject.optString("key", "").trim();
            if (name.isEmpty() && key.isEmpty()) {
                return fail("第 " + (i + 1) + " 学科缺少名称（name 或 key 至少填一个）");
            }
            if (name.isEmpty()) {
                name = key;
            }
            if (key.isEmpty()) {
                key = QuestionBank.deriveKey(name, i);
            }
            Integer dup = usedKeys.get(key);
            if (dup != null) {
                return fail("第 " + (i + 1) + " 学科的 key「" + key
                        + "」与第 " + dup + " 学科重复，学科 key 必须唯一");
            }
            usedKeys.put(key, i + 1);

            JSONArray questions = subject.optJSONArray("questions");
            if (questions == null || questions.length() == 0) {
                return fail("第 " + (i + 1) + " 学科「" + name + "」没有题目");
            }
            for (int k = 0; k < questions.length(); k++) {
                String error = validateQuestion(questions.optJSONObject(k),
                        "第 " + (i + 1) + " 学科第 " + (k + 1) + " 题");
                if (error != null) {
                    return fail(error);
                }
            }
            total += questions.length();
            if (summary.length() > 0) {
                summary.append("、");
            }
            summary.append(name).append(" ").append(questions.length()).append(" 题");
        }
        if (total < MIN_QUESTIONS) {
            return fail("全库至少需要 " + MIN_QUESTIONS + " 道题，当前只有 " + total + " 道");
        }
        if (total > MAX_QUESTIONS) {
            return fail("题目过多，全库最多 " + MAX_QUESTIONS + " 道，当前 " + total + " 道");
        }
        String headline = title.isEmpty() ? "总题库" : title;
        return new Result(true, "校验通过：" + headline + " · " + subjects.length()
                + " 个学科（" + summary + "）", total, version,
                subjects.length(), true);
    }

    private static Result validateOld(JSONArray questions, String title, String version) {
        if (questions.length() < MIN_QUESTIONS) {
            return fail("至少需要 " + MIN_QUESTIONS + " 道题，当前只有 " + questions.length() + " 道");
        }
        if (questions.length() > MAX_QUESTIONS) {
            return fail("题目过多，最多 " + MAX_QUESTIONS + " 道");
        }
        for (int i = 0; i < questions.length(); i++) {
            String error = validateQuestion(questions.optJSONObject(i), "第 " + (i + 1) + " 题");
            if (error != null) {
                return fail(error);
            }
        }
        String name = title.isEmpty() ? "默认题库" : title;
        return new Result(true, "校验通过：旧格式单学科「" + name + "」共 "
                + questions.length() + " 题", questions.length(), version, 1, false);
    }

    /** 单题校验，prefix 形如「第 2 学科第 3 题」或「第 5 题」。返回 null 表示通过。 */
    private static String validateQuestion(JSONObject question, String prefix) {
        if (question == null) {
            return prefix + "不是 JSON 对象";
        }
        String text = question.optString("q", "").trim();
        if (text.isEmpty()) {
            text = question.optString("question", "").trim();
        }
        if (text.isEmpty()) {
            return prefix + "缺少题干（q 或 question）";
        }
        if (text.length() > 200) {
            return prefix + "题干过长（最多 200 字）";
        }
        JSONArray options = question.optJSONArray("options");
        if (options == null) {
            return prefix + "缺少 options 数组";
        }
        if (options.length() < 2 || options.length() > 6) {
            return prefix + "的选项数量必须是 2~6 个";
        }
        for (int k = 0; k < options.length(); k++) {
            Object rawOption = options.opt(k);
            if (rawOption instanceof JSONObject || rawOption instanceof JSONArray) {
                return prefix + "第 " + (k + 1) + " 个选项必须是文字或数字";
            }
            String option = String.valueOf(rawOption).trim();
            if (option.isEmpty() || "null".equals(option)) {
                return prefix + "第 " + (k + 1) + " 个选项为空";
            }
            if (option.length() > 60) {
                return prefix + "第 " + (k + 1) + " 个选项过长（最多 60 字）";
            }
        }
        int answer = resolveAnswer(question.opt("answer"), options);
        if (answer < 0 || answer >= options.length()) {
            return prefix + "的 answer 必须是 0~" + (options.length() - 1)
                    + " 的下标，或与某个选项文本完全一致";
        }
        Object difficulty = question.opt("difficulty");
        if (difficulty instanceof JSONObject || difficulty instanceof JSONArray) {
            return prefix + "的 difficulty 必须是字符串或数字";
        }
        Object category = question.opt("category");
        if (category instanceof JSONObject || category instanceof JSONArray) {
            return prefix + "的 category 必须是字符串或数字";
        }
        return null;
    }

    private static int resolveAnswer(Object raw, JSONArray options) {
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw != null && !(raw instanceof JSONObject) && !(raw instanceof JSONArray)) {
            String text = String.valueOf(raw).trim();
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                for (int i = 0; i < options.length(); i++) {
                    if (String.valueOf(options.opt(i)).trim().equals(text)) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /** 校验通过后备份旧题库并写入新题库。 */
    public static Result importJson(Context context, String json) {
        Result checked = validate(json);
        if (!checked.ok) {
            return checked;
        }
        File target = externalFile(context);
        File backup = backupFile(context);
        if (target == null || backup == null) {
            return fail("外部存储不可用，无法保存题库");
        }
        String content = json;
        boolean merged = false;
        if (checked.newFormat && checked.subjects == 1) {
            // 单学科上传：当前题库已有同学科 key 时只替换该学科，没有时追加；
            // 其余情况（整库多学科文件）整体替换。
            String mergedText = mergeSingleSubject(target, json);
            if (mergedText != null) {
                Result mergedCheck = validate(mergedText);
                if (mergedCheck.ok) {
                    content = mergedText;
                    checked = mergedCheck;
                    merged = true;
                }
            }
        }
        try {
            if (target.isFile() && target.length() <= MAX_BYTES) {
                copy(target, backup);
            } else {
                copyAsset(context, backup);
            }
            writeText(target, content);
            String how = merged ? "已按学科合并（旧题库已备份）" : "旧题库已备份";
            return new Result(true, "导入成功，共 " + checked.count + " 题，"
                    + checked.subjects + " 个学科（" + how + "）",
                    checked.count, checked.version, checked.subjects, checked.newFormat);
        } catch (IOException e) {
            return fail("写入失败：" + e.getMessage());
        }
    }

    /**
     * 把「只有一个学科」的新格式内容合并进当前题库：
     * 同学科 key → 替换该学科；新 key → 追加。当前题库不是新格式时返回 null（走整体替换）。
     */
    private static String mergeSingleSubject(File target, String json) {
        if (!target.isFile()) {
            return null;
        }
        try {
            String existing = readText(target);
            JSONObject root = new JSONObject(existing.trim());
            JSONArray existingSubjects = root.optJSONArray("subjects");
            if (existingSubjects == null || existingSubjects.length() == 0) {
                return null;
            }
            JSONArray payloadSubjects = new JSONObject(json).optJSONArray("subjects");
            if (payloadSubjects == null || payloadSubjects.length() != 1) {
                return null;
            }
            JSONObject payloadSubject = payloadSubjects.optJSONObject(0);
            if (payloadSubject == null) {
                return null;
            }
            String payloadKey = payloadSubject.optString("key", "").trim();
            String payloadName = payloadSubject.optString("name", "").trim();
            if (payloadKey.isEmpty()) {
                payloadKey = QuestionBank.deriveKey(
                        payloadName.isEmpty() ? "学科" : payloadName, 0);
            }
            if (payloadName.isEmpty()) {
                payloadName = payloadKey;
            }
            JSONArray merged = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < existingSubjects.length(); i++) {
                JSONObject subject = existingSubjects.optJSONObject(i);
                if (subject == null) {
                    continue;
                }
                String key = subject.optString("key", "").trim();
                if (key.isEmpty()) {
                    String name = subject.optString("name", "").trim();
                    key = QuestionBank.deriveKey(name.isEmpty() ? "学科" : name, i);
                }
                JSONObject copy = new JSONObject(subject.toString());
                if (key.equals(payloadKey)) {
                    copy.put("key", payloadKey);
                    copy.put("name", payloadName);
                    copy.put("questions", payloadSubject.optJSONArray("questions"));
                    replaced = true;
                }
                merged.put(copy);
            }
            if (!replaced) {
                JSONObject added = new JSONObject();
                added.put("key", payloadKey);
                added.put("name", payloadName);
                added.put("questions", payloadSubject.optJSONArray("questions"));
                merged.put(added);
            }
            JSONObject result = new JSONObject();
            result.put("version", 3);
            String title = root.optString("title", "").trim();
            if (title.isEmpty()) {
                title = new JSONObject(json).optString("title", "总题库");
            }
            result.put("title", title);
            result.put("subjects", merged);
            return result.toString(2);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Result rollback(Context context) {
        File target = externalFile(context);
        File backup = backupFile(context);
        if (target == null || backup == null) {
            return fail("外部存储不可用");
        }
        if (!backup.isFile()) {
            return fail("没有可回退的备份");
        }
        try {
            copy(backup, target);
            Result checked = validate(readText(target));
            return new Result(true, "已回退到上一版题库" + (checked.ok ? "，共 " + checked.count + " 题" : ""),
                    checked.count, checked.version);
        } catch (IOException e) {
            return fail("回退失败：" + e.getMessage());
        }
    }

    /** 处理 adb push 的 questions_import.json，以及 U 盘根目录的 questions*.json。 */
    public static File findPendingImport(Context context) {
        File inbox = inboxFile(context);
        if (inbox != null && inbox.isFile() && inbox.length() > 0
                && inbox.length() <= MAX_BYTES && !isIgnored(context, inbox)) {
            return inbox;
        }
        String[] names = {"questions_import.json", "questions.json"};
        for (File dir : candidateDirs()) {
            for (String name : names) {
                File file = new File(dir, name);
                try {
                    if (file.isFile() && file.length() > 0 && file.length() <= MAX_BYTES
                            && !isIgnored(context, file)) {
                        return file;
                    }
                } catch (SecurityException ignored) {
                    // 没有存储权限时跳过
                }
            }
        }
        return null;
    }

    private static File[] candidateDirs() {
        File[] storageRoots = null;
        try {
            storageRoots = new File("/storage").listFiles();
        } catch (SecurityException ignored) {
            // 无权限
        }
        java.util.List<File> dirs = new java.util.ArrayList<File>();
        for (String common : new String[]{"/mnt/usb", "/mnt/usb_storage", "/storage/usb0",
                "/storage/usb1", "/storage/usbotg"}) {
            File dir = new File(common);
            if (dir.isDirectory()) {
                dirs.add(dir);
            }
        }
        if (storageRoots != null) {
            for (File dir : storageRoots) {
                String name = dir.getName();
                if ("emulated".equals(name) || "self".equals(name)) {
                    continue;
                }
                if (dir.isDirectory()) {
                    dirs.add(dir);
                }
            }
        }
        return dirs.toArray(new File[0]);
    }

    public static void markIgnored(Context context, File file) {
        Prefs.get(context).setIgnoredImport(file.getAbsolutePath(), file.lastModified());
    }

    private static boolean isIgnored(Context context, File file) {
        Prefs prefs = Prefs.get(context);
        return file.getAbsolutePath().equals(prefs.getIgnoredImportPath())
                && file.lastModified() == prefs.getIgnoredImportMtime();
    }

    public static Result importFile(Context context, File file) {
        Result result;
        try {
            result = importJson(context, readText(file));
        } catch (IOException e) {
            result = fail("读取失败：" + e.getMessage());
        }
        renameAfterProcess(context, file, result.ok);
        return result;
    }

    private static void renameAfterProcess(Context context, File file, boolean ok) {
        String suffix = ok ? ".imported" : ".invalid";
        File target = new File(file.getAbsolutePath() + suffix);
        try {
            if (target.exists()) {
                target.delete();
            }
            if (!file.renameTo(target)) {
                markIgnored(context, file);
            }
        } catch (SecurityException e) {
            markIgnored(context, file);
        }
    }

    public static String readText(File file) throws IOException {
        if (file.length() > MAX_BYTES) {
            throw new IOException("文件超过 1MB 上限");
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } finally {
            reader.close();
        }
        return sb.toString();
    }

    private static void writeText(File file, String text) throws IOException {
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
        try {
            writer.write(text);
            writer.flush();
        } finally {
            writer.close();
        }
    }

    private static void copy(File source, File target) throws IOException {
        InputStream in = new FileInputStream(source);
        FileOutputStream out = new FileOutputStream(target);
        try {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            in.close();
            out.close();
        }
    }

    private static void copyAsset(Context context, File target) throws IOException {
        InputStream in = context.getAssets().open("questions.json");
        FileOutputStream out = new FileOutputStream(target);
        try {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            in.close();
            out.close();
        }
    }

    private static Result fail(String message) {
        return new Result(false, message, 0, "");
    }
}
