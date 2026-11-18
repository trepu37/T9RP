package dev.rut9.engine;

import java.util.HashMap;
import java.util.Map;

/** Таблица «контекст -> следующее слово -> счётчик» (биграммы/триграммы). */
public final class NextTable {
    private final Map<String, Map<String, Integer>> t = new HashMap<>();

    public synchronized void add(String key, String next, int n) {
        t.computeIfAbsent(key, k -> new HashMap<>()).merge(next, n, Integer::sum);
    }

    public synchronized int get(String key, String next) {
        Map<String, Integer> m = t.get(key);
        return m == null ? 0 : m.getOrDefault(next, 0);
    }

    public synchronized Map<String, Integer> row(String key) {
        if (key == null) return Map.of();
        Map<String, Integer> m = t.get(key);
        return m == null ? Map.of() : new HashMap<>(m);
    }

    public synchronized Map<String, Map<String, Integer>> snapshot() {
        Map<String, Map<String, Integer>> out = new HashMap<>();
        t.forEach((k, v) -> out.put(k, new HashMap<>(v)));
        return out;
    }

    public synchronized void putAll(Map<String, Map<String, Integer>> src) {
        if (src == null) return;
        src.forEach((k, v) -> {
            if (v != null) v.forEach((n, c) -> { if (c != null) add(k, n, c); });
        });
    }

    public synchronized void clear() {
        t.clear();
    }
}
