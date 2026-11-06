package com.family.quizlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 每题答题统计 + 「掌握即移出」。
 *
 * 统计按题目 id 累计，写在 SharedPreferences 里，跨开机会话保留：
 * answered / correct / wrong 三个计数。
 *
 * 移出的题不修改 questions.json（避免破坏整库回退），而是记一份 id 名单；
 * 同时把整道题写入 /sdcard/Android/data/<包名>/files/mastered.json 做备份，
 * 家长可以在统计页一键恢复，原题仍原样留在题库文件里。
 */
public class QuestionStats {

    private static final String KEY_COUNTS = "qstats_counts";
    private static final String KEY_REMOVED = "qstats_removed_items";
    private static final String MASTERED_FILE = "mastered.json";

    private static QuestionStats sInstance;

    private final Context appContext;
    private final SharedPreferences sp;

    public static class Entry {
        public int answered;
        public int correct;
        public int wrong;
    }

    private QuestionStats(Context context) {
        appContext = context.getApplicationContext();
        sp = appContext.getSharedPreferences("quiz_stats", Context.MODE_PRIVATE);
    }

    public static synchronized QuestionStats get(Context context) {
        if (sInstance == null) {
            sInstance = new QuestionStats(context.getApplicationContext());
        }
        return sInstance;
    }

    // ---------------- 计数 ----------------

    public synchronized Entry getEntry(String id) {
        Entry entry = new Entry();
        if (id == null || id.isEmpty()) {
            return entry;
        }
        JSONObject counts = readCounts();
        JSONObject item = counts.optJSONObject(id);
        if (item != null) {
            entry.answered = item.optInt("a", 0);
            entry.correct = item.optInt("c", 0);
            entry.wrong = item.optInt("w", 0);
        }
        return entry;
    }

    /** 一次作答：answered +1，答对 correct +1、答错 wrong +1。 */
    public synchronized void record(Question question, boolean correct) {
        if (question == null || question.id == null || question.id.isEmpty()) {
            return;
        }
        JSONObject counts = readCounts();
        JSONObject item = counts.optJSONObject(question.id);
        if (item == null) {
            item = new JSONObject();
            try {
                counts.put(question.id, item);
            } catch (JSONException ignored) {
                return;
            }
        }
        try {
            item.put("a", item.optInt("a", 0) + 1);
            item.put(correct ? "c" : "w", item.optInt(correct ? "c" : "w", 0) + 1);
        } catch (JSONException ignored) {
            // JSONObject.put(String, int) 实际不会失败
        }
        sp.edit().putString(KEY_COUNTS, counts.toString()).apply();

        Prefs prefs = Prefs.get(appContext);
        if (prefs.isAutoRemove() && correct && isMastered(question.id)
                && !isRemoved(question.id)) {
            removeQuestion(question);
        }
    }

    /** 是否已掌握：答对次数达到家长设置的阈值。 */
    public boolean isMastered(String id) {
        return getEntry(id).correct >= Prefs.get(appContext).getMasteredThreshold();
    }

    public synchronized void resetCounters() {
        sp.edit().remove(KEY_COUNTS).apply();
    }

    private JSONObject readCounts() {
        try {
            return new JSONObject(sp.getString(KEY_COUNTS, "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    // ---------------- 移出 / 恢复 ----------------

    public synchronized Set<String> getRemovedIds() {
        Set<String> ids = new HashSet<String>();
        JSONArray items = readRemovedItems();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            String id = item == null ? "" : item.optString("id", "");
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
        return ids;
    }

    public boolean isRemoved(String id) {
        return id != null && getRemovedIds().contains(id);
    }

    public synchronized int removedCount() {
        return readRemovedItems().length();
    }

    public synchronized boolean removeQuestion(Question question) {
        if (question == null || question.id == null || question.id.isEmpty()) {
            return false;
        }
        JSONArray items = readRemovedItems();
        for (int i = 0; i < items.length(); i++) {
            JSONObject old = items.optJSONObject(i);
            if (old != null && question.id.equals(old.optString("id", ""))) {
                return false;
            }
        }
        JSONObject item = new JSONObject();
        try {
            item.put("id", question.id);
            item.put("subject", question.subjectKey);
            item.put("subjectName", question.subjectName);
            item.put("q", question.text);
            JSONArray options = new JSONArray();
            for (String option : question.options) {
                options.put(option);
            }
            item.put("options", options);
            item.put("answer", question.answer);
            item.put("removedAt", System.currentTimeMillis());
            item.put("correct", getEntry(question.id).correct);
            items.put(item);
        } catch (JSONException ignored) {
            return false;
        }
        saveRemovedItems(items);
        return true;
    }

    public synchronized boolean restore(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        JSONArray items = readRemovedItems();
        JSONArray kept = new JSONArray();
        boolean found = false;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (id.equals(item.optString("id", ""))) {
                found = true;
                continue;
            }
            kept.put(item);
        }
        if (!found) {
            return false;
        }
        saveRemovedItems(kept);
        return true;
    }

    /** 全部恢复已移出的题，返回恢复数量。 */
    public synchronized int restoreAll() {
        int count = readRemovedItems().length();
        saveRemovedItems(new JSONArray());
        return count;
    }

    private JSONArray readRemovedItems() {
        try {
            return new JSONArray(sp.getString(KEY_REMOVED, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void saveRemovedItems(JSONArray items) {
        sp.edit().putString(KEY_REMOVED, items.toString()).apply();
        writeMasteredFile(items);
    }

    /** mastered.json：移出题的完整备份，家长换题库后仍能查到当初移出了什么。 */
    private void writeMasteredFile(JSONArray items) {
        File dir = appContext.getExternalFilesDir(null);
        if (dir == null) {
            return;
        }
        File target = new File(dir, MASTERED_FILE);
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("title", "已掌握并移出的题目（备份）");
            root.put("savedAt", System.currentTimeMillis());
            root.put("items", items);
            Writer writer = new OutputStreamWriter(new FileOutputStream(target), "UTF-8");
            try {
                writer.write(root.toString(2));
                writer.flush();
            } finally {
                writer.close();
            }
        } catch (Throwable ignored) {
            // 备份文件写不进去不影响内存里的移出名单
        }
    }

    // ---------------- 汇总 ----------------

    /** 某学科里已掌握的题数（不论是否已移出）。 */
    public int masteredCount(List<Question> questions) {
        int count = 0;
        for (Question question : questions) {
            if (isMastered(question.id)) {
                count++;
            }
        }
        return count;
    }

    /** 某学科里已移出的题数。 */
    public int removedCount(List<Question> questions) {
        Set<String> removed = getRemovedIds();
        int count = 0;
        for (Question question : questions) {
            if (removed.contains(question.id)) {
                count++;
            }
        }
        return count;
    }

    /** 题干截断（默认 30 字），用于统计列表与 /api/stats。 */
    public static String clipQuestion(String text, int max) {
        String value = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "…";
    }

    public static String clip30(String text) {
        return clipQuestion(text, 30);
    }

    /** 供统计页展示的完整列表（按学科顺序）。 */
    public List<Question> allQuestions(Context context) {
        List<Question> list = new ArrayList<Question>();
        QuestionBank.Bank bank = QuestionBank.loadBank(context);
        for (QuestionBank.Subject subject : bank.subjects) {
            list.addAll(subject.questions);
        }
        return list;
    }

    /** 只用于日志/诊断：当前统计条数。 */
    public int trackedCount() {
        return readCounts().length();
    }

    static String percentage(int part, int total) {
        if (total <= 0) {
            return "0%";
        }
        return String.format(Locale.US, "%d%%", Math.round(part * 100f / total));
    }
}
