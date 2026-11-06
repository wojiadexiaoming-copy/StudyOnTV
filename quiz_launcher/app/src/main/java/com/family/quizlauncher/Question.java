package com.family.quizlauncher;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.Locale;

public class Question {

    /** 题目唯一 id：题库里给了就用题库的，没给就用规范化题干哈希（稳定可复现）。 */
    public final String id;
    /** 所属学科的 key / 名称；内置兜底题固定为 math。 */
    public final String subjectKey;
    public final String subjectName;

    public final String text;
    public final String[] options;
    public final int answer;
    /**
     * 题目本身在考大小写（例如选项里同时出现 China / china，或题干提到「大小写」）。
     * 这类题判题时必须严格区分大小写，其余题只做容错比对。
     */
    public final boolean caseSensitive;

    public Question(String text, String[] options, int answer) {
        this(null, null, null, text, options, answer, false);
    }

    public Question(String text, String[] options, int answer, boolean caseSensitive) {
        this(null, null, null, text, options, answer, caseSensitive);
    }

    public Question(String id, String subjectKey, String subjectName,
                    String text, String[] options, int answer, boolean caseSensitive) {
        this.id = (id == null || id.trim().isEmpty()) ? hashId(text) : id.trim();
        this.subjectKey = subjectKey == null || subjectKey.trim().isEmpty()
                ? "default" : subjectKey.trim();
        this.subjectName = subjectName == null || subjectName.trim().isEmpty()
                ? this.subjectKey : subjectName.trim();
        this.text = text;
        this.options = options;
        this.answer = answer;
        this.caseSensitive = caseSensitive;
    }

    public String answerText() {
        if (answer >= 0 && answer < options.length) {
            return options[answer];
        }
        return "?";
    }

    /**
     * 规范化题干哈希：去首尾空白、合并连续空白、忽略大小写，再取 SHA-256 前 8 字节。
     * 同样的题干在任何设备、任何一次启动都会得到同一个 id，用于统计与去重。
     */
    public static String hashId(String text) {
        String normalized = normalizeText(text);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(normalized.getBytes(Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder("q");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format(Locale.US, "%02x", bytes[i]));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "q" + Integer.toHexString(normalized.hashCode());
        }
    }

    /** 规范化题干：与判题无关，只用于生成稳定 id。 */
    public static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u3000', ' ').trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
