package com.family.quizlauncher.ai;

import com.family.quizlauncher.Question;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一条思路提示。只保留启发式内容，不含答案。 */
public final class Hint {

    public final String hint;
    public final List<String> steps;
    public final String concept;
    public final String pitfall;
    public final boolean needMoreInfo;
    /** true 表示本地兜底提示，不是 AI 生成。 */
    public final boolean local;

    public Hint(String hint, List<String> steps, String concept, String pitfall,
                boolean needMoreInfo, boolean local) {
        this.hint = hint == null ? "" : hint;
        this.steps = steps == null ? Collections.<String>emptyList() : steps;
        this.concept = concept == null ? "" : concept;
        this.pitfall = pitfall == null ? "" : pitfall;
        this.needMoreInfo = needMoreInfo;
        this.local = local;
    }

    public boolean isUsable() {
        return !hint.isEmpty() || !steps.isEmpty();
    }

    /** 朗读文本：只读 hint + 前两步，限制在 80 字以内。 */
    public String speechText() {
        StringBuilder sb = new StringBuilder(hint);
        for (String step : steps) {
            if (sb.length() >= 80) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('。');
            }
            sb.append(step);
        }
        String text = sb.toString().trim();
        if (text.length() > 80) {
            text = text.substring(0, 80);
        }
        return text;
    }

    /** 无 Key / 断网 / 超时 / 泄露拦截时使用的本地通用思路，0 延迟、不联网。 */
    public static Hint localFor(Question question) {
        String text = question == null ? "" : question.text;
        List<String> steps = new ArrayList<String>();
        String hint;
        String concept;
        if (text.contains("不同类")) {
            hint = "先把每个单词的意思说一说，再比较它们属于哪一类。";
            steps.add("逐个说出每个单词的中文意思");
            steps.add("找出两个同类、一个不同类的那个词");
            concept = "词汇分类";
        } else if (text.toLowerCase().contains("sharing")) {
            hint = "回想课文里表示「分享」的句型和词汇，再逐项放回语境里读一遍。";
            steps.add("圈出题干里的关键词 sharing");
            steps.add("把每个选项代入语境，排除语义不通的");
            concept = "情景交际";
        } else if (question != null && hasLatin(text)) {
            hint = "先通读题干，注意关键词和句子结构，再把每个选项放回原句读一遍。";
            steps.add("找出题干里的关键词和时态线索");
            steps.add("逐个代入选项，用排除法检查");
            concept = "英语审题与排除";
        } else {
            hint = "先把题目读一遍，圈出关键信息，再一步一步排除。";
            steps.add("找出题目在问什么");
            steps.add("把明显不对的选项先排除");
            concept = "审题与排除";
        }
        return new Hint(hint, steps, concept, "", false, true);
    }

    private static boolean hasLatin(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }
}
