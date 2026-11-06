package com.family.quizlauncher.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/** 题目文本 → 提示的内存 LRU 缓存。题量很小，不落盘，退出进程即清空。 */
public final class HintCache {

    private static final int MAX_ENTRIES = 50;
    private static final HintCache INSTANCE = new HintCache();

    private final Map<String, Hint> map = new LinkedHashMap<String, Hint>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Hint> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private HintCache() {
    }

    public static HintCache get() {
        return INSTANCE;
    }

    public synchronized Hint get(String questionText) {
        if (questionText == null) {
            return null;
        }
        return map.get(questionText);
    }

    public synchronized void put(String questionText, Hint hint) {
        if (questionText == null || hint == null) {
            return;
        }
        map.put(questionText, hint);
    }

    public synchronized void clear() {
        map.clear();
    }
}
