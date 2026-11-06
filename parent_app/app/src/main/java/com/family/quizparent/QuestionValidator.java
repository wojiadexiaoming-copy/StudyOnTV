package com.family.quizparent;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 手机端题库离线校验，规则与电视端 QuestionImporter 保持一致：
 * 支持 v3 多学科格式（subjects）与 v2 旧格式（questions / 裸数组）。
 * 题数 5~500、每题 2~6 个选项、answer 为下标或与选项原文完全一致、≤1MB。
 * 纯逻辑，不依赖 Android API，可直接在 JVM / app_process 里跑。
 */
public final class QuestionValidator {

    public static final int MAX_BYTES = 1024 * 1024;
    private static final int MIN_QUESTIONS = 5;
    private static final int MAX_QUESTIONS = 500;

    public static final class SubjectInfo {
        public final String key;
        public final String name;
        public final int count;

        SubjectInfo(String key, String name, int count) {
            this.key = key;
            this.name = name;
            this.count = count;
        }
    }

    public static final class Result {
        public final boolean ok;
        public final String message;
        public final int count;
        /** v3 多学科时的学科拆分；v2 旧格式为空列表。 */
        public final List<SubjectInfo> subjects;

        Result(boolean ok, String message, int count, List<SubjectInfo> subjects) {
            this.ok = ok;
            this.message = message;
            this.count = count;
            this.subjects = subjects;
        }

        public boolean isMultiSubject() {
            return !subjects.isEmpty();
        }
    }

    private QuestionValidator() {
    }

    public static Result validate(String json) {
        if (json == null || json.trim().isEmpty()) {
            return fail("内容为空");
        }
        // 兼容从聊天/网页复制来的内容：去掉 BOM 再校验，文件导入和粘贴导入共用。
        String trimmed = json.replace("\uFEFF", "").trim();
        try {
            if (trimmed.getBytes("UTF-8").length > MAX_BYTES) {
                return fail("文件超过 1MB 上限");
            }
        } catch (Exception ignored) {
            return fail("文件编码无法识别");
        }
        try {
            if (trimmed.startsWith("[")) {
                JSONArray array = new JSONArray(trimmed);
                return validateLegacy(array, false);
            }
            JSONObject root = new JSONObject(trimmed);
            JSONArray subjects = root.optJSONArray("subjects");
            if (subjects != null) {
                return validateSubjects(subjects);
            }
            JSONArray questions = root.optJSONArray("questions");
            if (questions == null) {
                return fail("JSON 里既没有 subjects 数组，也没有 questions 数组");
            }
            return validateLegacy(questions, true);
        } catch (JSONException e) {
            String detail = e.getMessage();
            return fail("JSON 格式错误" + (detail == null ? "" : "：" + detail));
        } catch (Throwable t) {
            return fail("校验失败：" + t.getClass().getSimpleName());
        }
    }

    // ---------------- v3 多学科 ----------------

    private static Result validateSubjects(JSONArray subjects) {
        if (subjects.length() == 0) {
            return fail("subjects 数组里没有学科");
        }
        int total = 0;
        List<SubjectInfo> infos = new ArrayList<SubjectInfo>();
        Set<String> keys = new HashSet<String>();
        Set<String> ids = new HashSet<String>();
        for (int s = 0; s < subjects.length(); s++) {
            JSONObject subject = subjects.optJSONObject(s);
            String prefix = "第 " + (s + 1) + " 学科";
            if (subject == null) {
                return fail(prefix + "不是 JSON 对象");
            }
            String key = subject.optString("key", "").trim();
            if (key.isEmpty()) {
                return fail(prefix + "缺少 key");
            }
            if (key.length() > 40) {
                return fail(prefix + "的 key 过长（最多 40 字符）");
            }
            if (!keys.add(key)) {
                return fail(prefix + "的 key「" + key + "」重复");
            }
            String name = subject.optString("name", "").trim();
            if (name.isEmpty()) {
                return fail(prefix + "缺少 name（学科名称）");
            }
            prefix = prefix + "（" + name + "）";
            JSONArray questions = subject.optJSONArray("questions");
            if (questions == null) {
                return fail(prefix + "缺少 questions 数组");
            }
            if (questions.length() == 0) {
                return fail(prefix + "至少需要 1 道题");
            }
            for (int q = 0; q < questions.length(); q++) {
                JSONObject question = questions.optJSONObject(q);
                String at = prefix + "第 " + (q + 1) + " 题";
                if (question == null) {
                    return fail(at + "不是 JSON 对象");
                }
                String id = question.optString("id", "").trim();
                if (id.isEmpty()) {
                    return fail(at + "缺少 id（每题需要唯一编号）");
                }
                if (id.length() > 64) {
                    return fail(at + "的 id 过长（最多 64 字符）");
                }
                if (!ids.add(id)) {
                    return fail(at + "的 id「" + id + "」与前面的题目重复");
                }
                String error = validateQuestion(question, at);
                if (error != null) {
                    return fail(error);
                }
            }
            total += questions.length();
            if (total > MAX_QUESTIONS) {
                return fail("题目过多，最多 " + MAX_QUESTIONS + " 道");
            }
            infos.add(new SubjectInfo(key, name, questions.length()));
        }
        if (total < MIN_QUESTIONS) {
            return fail("至少需要 " + MIN_QUESTIONS + " 道题，当前只有 " + total + " 道");
        }
        return new Result(true, "校验通过：" + summaryText(total, infos), total, infos);
    }

    // ---------------- v2 旧格式 ----------------

    private static Result validateLegacy(JSONArray array, boolean hasRoot) {
        if (array.length() < MIN_QUESTIONS) {
            return fail("至少需要 " + MIN_QUESTIONS + " 道题，当前只有 " + array.length() + " 道");
        }
        if (array.length() > MAX_QUESTIONS) {
            return fail("题目过多，最多 " + MAX_QUESTIONS + " 道");
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject question = array.optJSONObject(i);
            String at = "第 " + (i + 1) + " 题";
            if (question == null) {
                return fail(at + "不是 JSON 对象");
            }
            String error = validateQuestion(question, at);
            if (error != null) {
                return fail(error);
            }
        }
        return new Result(true, "校验通过，共 " + array.length() + " 题", array.length(),
                new ArrayList<SubjectInfo>());
    }

    /** 单题校验；返回 null 表示通过。prefix 类似「第 1 学科（英语）第 2 题」。 */
    private static String validateQuestion(JSONObject question, String at) {
        String text = question.optString("q", "").trim();
        if (text.isEmpty()) {
            text = question.optString("question", "").trim();
        }
        if (text.isEmpty()) {
            return at + "缺少题干（q 或 question）";
        }
        if (text.length() > 200) {
            return at + "题干过长（最多 200 字）";
        }
        JSONArray options = question.optJSONArray("options");
        if (options == null) {
            return at + "缺少 options 数组";
        }
        if (options.length() < 2 || options.length() > 6) {
            return at + "的选项数量必须是 2~6 个，当前 " + options.length() + " 个";
        }
        for (int k = 0; k < options.length(); k++) {
            Object rawOption = options.opt(k);
            if (rawOption instanceof JSONObject || rawOption instanceof JSONArray) {
                return at + "第 " + (k + 1) + " 个选项必须是文字或数字";
            }
            String option = String.valueOf(rawOption).trim();
            if (option.isEmpty() || "null".equals(option)) {
                return at + "第 " + (k + 1) + " 个选项为空";
            }
            if (option.length() > 60) {
                return at + "第 " + (k + 1) + " 个选项过长（最多 60 字）";
            }
        }
        int answer = resolveAnswer(question.opt("answer"), options);
        if (answer < 0 || answer >= options.length()) {
            return at + "的 answer 必须是 0~" + (options.length() - 1)
                    + " 的下标，或与某个选项文本完全一致";
        }
        Object difficulty = question.opt("difficulty");
        if (difficulty instanceof JSONObject || difficulty instanceof JSONArray) {
            return at + "的 difficulty 必须是字符串或数字";
        }
        Object category = question.opt("category");
        if (category instanceof JSONObject || category instanceof JSONArray) {
            return at + "的 category 必须是字符串或数字";
        }
        return null;
    }

    private static String summaryText(int total, List<SubjectInfo> infos) {
        StringBuilder sb = new StringBuilder("共 ").append(total).append(" 题（");
        for (int i = 0; i < infos.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(infos.get(i).name).append(" ").append(infos.get(i).count).append(" 题");
        }
        return sb.append("）").toString();
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

    private static Result fail(String message) {
        return new Result(false, message, 0, new ArrayList<SubjectInfo>());
    }
}
