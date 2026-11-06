package com.family.quizlauncher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 题库加载顺序：
 * 1) /sdcard/Android/data/<包名>/files/questions.json（可用 adb push 覆盖，无需重装）
 * 2) SharedPreferences 里的 custom_questions（预留给后续换题入口）
 * 3) assets/questions.json（内置算术题，保证开箱可用）
 *
 * v2.9 起支持两种格式：
 * - 新格式 {"version":3,"title":"总题库","subjects":[{"key","name","questions":[...]}]}
 * - 旧格式 {"version":"2","questions":[...]} 或纯数组，整体视为一个学科，学科名取 title。
 */
public class QuestionBank {

    private static final String ASSET_FILE = "questions.json";
    private static final String EXTERNAL_FILE = "questions.json";
    private static final String DEFAULT_SUBJECT_NAME = "默认题库";
    private static final String DEFAULT_SUBJECT_KEY = "default";

    /** 一个学科及其题目。 */
    public static class Subject {
        public final String key;
        public final String name;
        public final List<Question> questions = new ArrayList<Question>();

        public Subject(String key, String name) {
            this.key = key;
            this.name = name;
        }
    }

    /** 整本题库。 */
    public static class Bank {
        public String title = "";
        public final List<Subject> subjects = new ArrayList<Subject>();

        public List<Question> all() {
            List<Question> list = new ArrayList<Question>();
            for (Subject subject : subjects) {
                list.addAll(subject.questions);
            }
            return list;
        }

        public Subject find(String key) {
            for (Subject subject : subjects) {
                if (subject.key.equals(key)) {
                    return subject;
                }
            }
            return null;
        }

        public int total() {
            return all().size();
        }
    }

    /** 原始整库（不做启用学科/已移出的过滤），供统计与家长页使用。 */
    public static Bank loadBank(Context context) {
        String json = readRaw(context);
        Bank bank = parseBank(json);
        if (bank.total() == 0) {
            return builtinBank();
        }
        return bank;
    }

    /** 本轮可抽的题池：只取已启用学科，且排除已移出的题。 */
    public static List<Question> load(Context context) {
        Bank bank = loadBank(context);
        Prefs prefs = Prefs.get(context);
        Set<String> removed = QuestionStats.get(context).getRemovedIds();

        List<Question> pool = new ArrayList<Question>();
        for (Subject subject : bank.subjects) {
            if (!prefs.isSubjectEnabled(subject.key)) {
                continue;
            }
            for (Question question : subject.questions) {
                if (!removed.contains(question.id)) {
                    pool.add(question);
                }
            }
        }
        if (pool.isEmpty()) {
            // 启用的学科全被移出：退到所有学科（仍排除已移出）。
            for (Subject subject : bank.subjects) {
                for (Question question : subject.questions) {
                    if (!removed.contains(question.id)) {
                        pool.add(question);
                    }
                }
            }
        }
        if (pool.isEmpty()) {
            // 全库都被移出：把移出的题放回来，避免孩子永远无法答题而卡死。
            pool = bank.all();
        }
        return pool;
    }

    /**
     * 抽题：随机打乱后取前 count 道，同一轮尽量不重复题干（规范化题干相同只取第一道），
     * 题池不够再去重限制补足。
     */
    public static List<Question> pick(Context context, int count) {
        List<Question> pool = new ArrayList<Question>(load(context));
        if (pool.isEmpty()) {
            return pool;
        }
        Collections.shuffle(pool);
        List<Question> picked = new ArrayList<Question>();
        Set<String> seen = new HashSet<String>();
        for (Question question : pool) {
            if (picked.size() >= count) {
                break;
            }
            String normalized = Question.normalizeText(question.text);
            if (!seen.contains(normalized)) {
                seen.add(normalized);
                picked.add(question);
            }
        }
        for (Question question : pool) {
            if (picked.size() >= count) {
                break;
            }
            if (!picked.contains(question)) {
                picked.add(question);
            }
        }
        return picked;
    }

    /** 该 id 属于哪个学科（整库范围，含已移出的题）。 */
    public static Subject subjectOf(Bank bank, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Subject subject : bank.subjects) {
            for (Question question : subject.questions) {
                if (id.equals(question.id)) {
                    return subject;
                }
            }
        }
        return null;
    }

    public static Question findById(Bank bank, String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Subject subject : bank.subjects) {
            for (Question question : subject.questions) {
                if (id.equals(question.id)) {
                    return question;
                }
            }
        }
        return null;
    }

    private static String readRaw(Context context) {
        String json = null;
        File external = null;
        try {
            external = context.getExternalFilesDir(null);
        } catch (Exception ignored) {
            // 外部存储不可用时直接使用内置题库
        }
        if (external != null) {
            File file = new File(external, EXTERNAL_FILE);
            if (file.isFile()) {
                json = readFile(file);
            }
        }
        if (json == null || json.trim().isEmpty()) {
            String custom = Prefs.get(context).getCustomQuestions();
            if (custom != null && !custom.trim().isEmpty()) {
                json = custom;
            }
        }
        if (json == null || json.trim().isEmpty()) {
            json = readAsset(context);
        }
        return json;
    }

    private static String readAsset(Context context) {
        InputStream in = null;
        try {
            in = context.getAssets().open(ASSET_FILE);
            return readStream(in);
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static String readFile(File file) {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            return readStream(in);
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static String readStream(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    /** 宽松解析：跳过坏题，保证题库文件局部损坏时仍能答题。 */
    public static Bank parseBank(String json) {
        Bank bank = new Bank();
        if (json == null) {
            return bank;
        }
        String trimmed = json.trim();
        if (trimmed.isEmpty()) {
            return bank;
        }
        try {
            JSONObject root = trimmed.startsWith("[") ? null : new JSONObject(trimmed);
            if (root == null) {
                // 纯数组 = 旧的单题库格式
                bank.title = DEFAULT_SUBJECT_NAME;
                parseInto(bank, new Subject(DEFAULT_SUBJECT_KEY, DEFAULT_SUBJECT_NAME),
                        new JSONArray(trimmed));
                return bank;
            }
            bank.title = root.optString("title", "").trim();
            JSONArray subjects = root.optJSONArray("subjects");
            if (subjects != null) {
                for (int i = 0; i < subjects.length(); i++) {
                    JSONObject item = subjects.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String name = item.optString("name", "").trim();
                    String key = item.optString("key", "").trim();
                    if (name.isEmpty()) {
                        name = key.isEmpty() ? ("学科" + (i + 1)) : key;
                    }
                    if (key.isEmpty()) {
                        key = deriveKey(name, i);
                    }
                    Subject subject = bank.find(key);
                    if (subject == null) {
                        subject = new Subject(key, name);
                        bank.subjects.add(subject);
                    }
                    parseInto(bank, subject, item.optJSONArray("questions"));
                }
                return bank;
            }
            JSONArray questions = root.optJSONArray("questions");
            if (questions != null) {
                String name = bank.title.isEmpty() ? DEFAULT_SUBJECT_NAME : bank.title;
                String key = deriveKey(name, 0);
                bank.title = name;
                parseInto(bank, new Subject(key, name), questions);
            }
        } catch (JSONException ignored) {
            // 题库格式错误时返回已解析到的部分
        }
        return bank;
    }

    private static void parseInto(Bank bank, Subject subject, JSONArray array) {
        if (array == null) {
            return;
        }
        boolean had = bank.subjects.contains(subject);
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) {
                continue;
            }
            String text = obj.optString("q", "").trim();
            if (text.isEmpty()) {
                text = obj.optString("question", "").trim();
            }
            JSONArray optionArray = obj.optJSONArray("options");
            if (text.isEmpty() || optionArray == null || optionArray.length() < 2) {
                continue;
            }
            String[] options = new String[optionArray.length()];
            for (int k = 0; k < options.length; k++) {
                // 严格保留 JSON 原文大小写，不做任何 toLowerCase/toUpperCase。
                options[k] = String.valueOf(optionArray.opt(k));
            }
            boolean caseSensitive = isCaseSensitiveQuestion(text, options);
            int answer = resolveAnswer(obj.opt("answer"), options, caseSensitive);
            if (answer < 0 || answer >= options.length) {
                continue;
            }
            String id = obj.optString("id", "").trim();
            subject.questions.add(new Question(id, subject.key, subject.name,
                    text, options, answer, caseSensitive));
        }
        if (!had && !subject.questions.isEmpty()) {
            bank.subjects.add(subject);
        }
    }

    /**
     * 缺省学科 key 的生成规则：取 name 的小写字母数字，非法字符转 '-'，全中文时退化为 subjectN。
     */
    public static String deriveKey(String name, int index) {
        String raw = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        boolean lastDash = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (ok) {
                sb.append(c);
                lastDash = c == '-';
            } else if (!lastDash && sb.length() > 0) {
                sb.append('-');
                lastDash = true;
            }
        }
        String slug = sb.toString().replaceAll("-+", "-").replaceAll("^-|-$", "");
        if (slug.isEmpty()) {
            return "subject" + (index + 1);
        }
        return slug;
    }

    /**
     * 判题用的归一化：去首尾空格、合并多余空格、去首尾标点。
     * 不使用任何大小写转换，大小写策略由 caseSensitive 参数决定。
     */
    private static String normalizeForCompare(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replaceAll("\\s+", " ");
        int start = 0;
        int end = s.length();
        while (start < end && !Character.isLetterOrDigit(s.charAt(start))) {
            start++;
        }
        while (end > start && !Character.isLetterOrDigit(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    /**
     * 题目本身是否在考大小写：
     * 1) 两个及以上选项忽略大小写后完全相同（如 China / china）；
     * 2) 题干里出现「大小写 / 大写 / 小写 / capital letter / uppercase / lowercase」等字样。
     */
    private static boolean isCaseSensitiveQuestion(String text, String[] options) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("大小写") || lower.contains("大写") || lower.contains("小写")
                || lower.contains("capital letter") || lower.contains("uppercase")
                || lower.contains("lowercase") || lower.contains("case sensitive")
                || lower.contains("case-sensitive")) {
            return true;
        }
        String[] normalized = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            normalized[i] = normalizeForCompare(options[i]);
        }
        for (int i = 0; i < normalized.length; i++) {
            for (int j = i + 1; j < normalized.length; j++) {
                if (normalized[i].equalsIgnoreCase(normalized[j])
                        && !normalized[i].equals(normalized[j])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int resolveAnswer(Object raw, String[] options, boolean caseSensitive) {
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw != null) {
            String text = String.valueOf(raw).trim();
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                String target = normalizeForCompare(text);
                for (int i = 0; i < options.length; i++) {
                    String option = normalizeForCompare(options[i]);
                    boolean match = caseSensitive
                            ? option.equals(target)
                            : option.equalsIgnoreCase(target);
                    if (match) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /** assets 也被破坏时的最后兜底，保证关卡永远可用。 */
    public static Bank builtinBank() {
        Bank bank = new Bank();
        bank.title = "内置算术题";
        Subject subject = new Subject("math", "数学");
        bank.subjects.add(subject);
        List<Question> list = new ArrayList<Question>();
        list.add(new Question("3 + 5 = ?", new String[]{"7", "8", "9"}, 1));
        list.add(new Question("9 - 4 = ?", new String[]{"5", "4", "6"}, 0));
        list.add(new Question("6 × 3 = ?", new String[]{"15", "18", "21"}, 1));
        list.add(new Question("12 ÷ 4 = ?", new String[]{"2", "3", "4"}, 1));
        list.add(new Question("7 + 8 = ?", new String[]{"14", "15", "16"}, 1));
        list.add(new Question("20 - 9 = ?", new String[]{"11", "10", "12"}, 0));
        for (Question question : list) {
            subject.questions.add(new Question(null, subject.key, subject.name,
                    question.text, question.options, question.answer, false));
        }
        return bank;
    }
}
