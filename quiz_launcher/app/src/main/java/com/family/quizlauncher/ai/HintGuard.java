package com.family.quizlauncher.ai;

import com.family.quizlauncher.Question;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 客户端答案泄露拦截。
 *
 * 实测 Agnes 会主动写出答案（例如「三六十八」「误算成 6 或 9」），所以 prompt 之外
 * 必须再做一层本地校验：提示文本里出现正确选项原文、正确选项字母、或能直接算出
 * 答案的关键数字，就整条丢弃，UI 降级为本地通用思路。
 */
public final class HintGuard {

    private static final Pattern CHINESE_NUMERAL_BURST =
            Pattern.compile("[一二三四五六七八九十百千万零两]{2,}");

    private HintGuard() {
    }

    /** 返回清洗后的提示；命中泄露或为空时返回 null，由调用方降级。 */
    public static Hint sanitize(Hint raw, Question question) {
        if (raw == null || question == null) {
            return null;
        }
        String hint = trimTo(raw.hint, 60);
        List<String> steps = new ArrayList<String>();
        for (String step : raw.steps) {
            if (step != null) {
                String clean = trimTo(step, 40);
                if (!clean.isEmpty()) {
                    steps.add(clean);
                }
            }
            if (steps.size() >= 3) {
                break;
            }
        }
        String concept = trimTo(raw.concept, 20);
        String pitfall = trimTo(raw.pitfall, 40);
        // 常见错误描述里出现阿拉伯数字多半是在举例错误结果，直接清空。
        if (containsDigit(pitfall)) {
            pitfall = "";
        }
        if (hint.isEmpty() && !steps.isEmpty()) {
            hint = joinSteps(steps);
        }
        if (hint.isEmpty()) {
            return null;
        }
        if (leaks(hint, question) || leaks(concept, question) || leaks(pitfall, question)) {
            return null;
        }
        for (String step : steps) {
            if (leaks(step, question)) {
                return null;
            }
        }
        return new Hint(hint, steps, concept, pitfall, raw.needMoreInfo, false);
    }

    /** 是否泄露答案。所有字段都会先做长度/空值保护。 */
    public static boolean leaks(String text, Question question) {
        if (text == null || text.trim().isEmpty() || question == null) {
            return false;
        }
        String answerText = question.answerText();
        if (answerText == null || answerText.isEmpty()) {
            return false;
        }
        String answerNorm = normalize(answerText);
        if (answerNorm.length() >= 2) {
            String textNorm = normalize(text);
            if (question.caseSensitive) {
                if (normalizeKeepCase(text).contains(normalizeKeepCase(answerText))) {
                    return true;
                }
            } else if (textNorm.contains(answerNorm)) {
                return true;
            }
        }
        if (containsAnswerLetter(text, question.answer)) {
            return true;
        }
        if (mentionsNumericAnswer(text, answerText)) {
            return true;
        }
        if (containsChineseNumeralAnswer(text, question.text)) {
            return true;
        }
        return false;
    }

    private static boolean containsAnswerLetter(String text, int answerIndex) {
        if (answerIndex < 0 || answerIndex > 3) {
            return false;
        }
        String word = String.valueOf((char) ('A' + answerIndex));
        String lower = text.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int idx = lower.indexOf(word.toLowerCase(Locale.ROOT), from);
            if (idx < 0) {
                return false;
            }
            int after = idx + 1;
            if (after >= lower.length() || !isAsciiLetter(lower.charAt(after))) {
                String before = lower.substring(Math.max(0, idx - 8), idx);
                if (before.contains("答案") || before.contains("选") || before.contains("option")
                        || before.contains("answer") || before.contains("项")) {
                    return true;
                }
            }
            from = idx + 1;
        }
    }

    private static boolean mentionsNumericAnswer(String text, String answerText) {
        String digits = answerText.trim();
        if (!digits.matches("-?\\d+(\\.\\d+)?")) {
            return false;
        }
        Pattern pattern = Pattern.compile("(?<![0-9])" + Pattern.quote(digits) + "(?![0-9])");
        return pattern.matcher(text).find();
    }

    /**
     * 中文口诀（如「三六十八」）没法靠数字匹配，用「连续中文数字 ≥2 字且题干里没有」
     * 的保守规则拦掉。
     */
    private static boolean containsChineseNumeralAnswer(String text, String questionText) {
        Matcher matcher = CHINESE_NUMERAL_BURST.matcher(text);
        String source = questionText == null ? "" : questionText;
        while (matcher.find()) {
            String burst = matcher.group();
            if (!source.contains(burst)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean containsDigit(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isDigit(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static String trimTo(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String clean = text.replace("\r", " ").replace("\n", " ").trim();
        while (clean.contains("  ")) {
            clean = clean.replace("  ", " ");
        }
        if (clean.length() > maxChars) {
            clean = clean.substring(0, maxChars);
        }
        return clean.trim();
    }

    private static String joinSteps(List<String> steps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size() && i < 2; i++) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(steps.get(i));
        }
        return sb.toString();
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String s = text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
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

    private static String normalizeKeepCase(String text) {
        if (text == null) {
            return "";
        }
        String s = text.trim().replaceAll("\\s+", " ");
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
}
