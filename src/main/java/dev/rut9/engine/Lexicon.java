package dev.rut9.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;

/**
 * Словарь с частотами + индекс SymSpell (удаления до 2 символов, префикс 6)
 * для быстрого поиска исправлений, + отсортированное множество для поиска по префиксу.
 * Все публичные методы синхронизированы.
 */
public final class Lexicon {
    private static final int PREFIX = 6;
    private static final int MAX_DIST = 2;

    public record Cand(String word, int dist) {}

    private final Map<String, Integer> freq = new HashMap<>();
    private final Map<String, List<String>> index = new HashMap<>();
    private final TreeSet<String> sorted = new TreeSet<>();

    private static String norm(String w) {
        return w.toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    /**
     * Формат: строки «слово» (много слов в строке допустимо, порядок = убывание частоты)
     * или «слово число» (явная частота). Строки с # — комментарии.
     */
    public void load(BufferedReader r) throws IOException {
        String line;
        int rank = 0;
        while ((line = r.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] t = line.split("\\s+");
            if (t.length == 2 && t[1].matches("\\d+")) {
                add(t[0], Integer.parseInt(t[1]));
                continue;
            }
            for (String w : t) {
                add(w, (int) (3_000_000L / (rank + 30)));
                rank++;
            }
        }
    }

    /** РП-словарь: все слова файла получают одну и ту же частоту f (порядок не важен). */
    public void loadFlat(BufferedReader r, int f) throws IOException {
        String line;
        while ((line = r.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            for (String w : line.split("\\s+")) add(w, f);
        }
    }

    /**
     * Большой частотный словарь (строки «слово частота», по убыванию частоты).
     * Все слова попадают в список «известных» (их не будут «исправлять») и в поиск по префиксу,
     * но индекс для исправления опечаток (SymSpell) строится только для первых indexTop слов — иначе не хватит памяти.
     * Частота пересчитывается из ранга в ту же шкалу, что у встроенного словаря.
     */
    public void loadBig(BufferedReader r, int maxWords, int indexTop) throws IOException {
        String line;
        int rank = 0;
        while (rank < maxWords && (line = r.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int sp = line.indexOf(' ');
            String w = norm(sp < 0 ? line : line.substring(0, sp));
            if (!plainWord(w)) continue;
            addBig(w, (int) Math.max(1, 3_000_000L / (rank + 30)), rank < indexTop);
            rank++;
        }
    }

    private static boolean plainWord(String w) {
        if (w.length() < 2 || w.length() > 30) return false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!((c >= 'а' && c <= 'я') || c == '-')) return false;
        }
        return w.charAt(0) != '-' && w.charAt(w.length() - 1) != '-';
    }

    private synchronized void addBig(String word, int f, boolean indexIt) {
        Integer old = freq.get(word);
        if (old != null) {
            if (f > old) freq.put(word, f);
            return;
        }
        freq.put(word, f);
        sorted.add(word);
        if (!indexIt) return;
        String p = word.length() > PREFIX ? word.substring(0, PREFIX) : word;
        for (String d : deletesOf(p)) index.computeIfAbsent(d, k -> new ArrayList<>(2)).add(word);
    }

    public synchronized void add(String word, int f) {
        word = norm(word);
        if (word.isEmpty()) return;
        Integer old = freq.get(word);
        if (old != null) {
            if (f > old) freq.put(word, f);
            return;
        }
        freq.put(word, f);
        sorted.add(word);
        String p = word.length() > PREFIX ? word.substring(0, PREFIX) : word;
        for (String d : deletesOf(p)) index.computeIfAbsent(d, k -> new ArrayList<>(2)).add(word);
    }

    private static Set<String> deletesOf(String p) {
        Set<String> out = new HashSet<>();
        out.add(p);
        Set<String> level = new HashSet<>();
        level.add(p);
        for (int d = 0; d < MAX_DIST; d++) {
            Set<String> next = new HashSet<>();
            for (String s : level) {
                if (s.length() <= 1) continue;
                for (int i = 0; i < s.length(); i++) next.add(s.substring(0, i) + s.substring(i + 1));
            }
            out.addAll(next);
            level = next;
        }
        return out;
    }

    public synchronized boolean contains(String w) {
        return freq.containsKey(norm(w));
    }

    public synchronized int freq(String w) {
        return freq.getOrDefault(norm(w), 0);
    }

    public synchronized int size() {
        return freq.size();
    }

    public synchronized List<Cand> lookup(String input, int maxDist) {
        input = norm(input);
        String p = input.length() > PREFIX ? input.substring(0, PREFIX) : input;
        Set<String> cands = new HashSet<>();
        for (String d : deletesOf(p)) {
            List<String> l = index.get(d);
            if (l != null) cands.addAll(l);
        }
        List<Cand> res = new ArrayList<>();
        for (String c : cands) {
            if (Math.abs(c.length() - input.length()) > maxDist) continue;
            int dist = osa(input, c, maxDist);
            if (dist <= maxDist) res.add(new Cand(c, dist));
        }
        return res;
    }

    public synchronized List<String> prefixSearch(String prefix, int limit) {
        NavigableSet<String> sub = sorted.subSet(prefix, true, prefix + '\uffff', true);
        PriorityQueue<String> pq = new PriorityQueue<>(Comparator.comparingInt((String w) -> freq.get(w)));
        for (String w : sub) {
            pq.add(w);
            if (pq.size() > limit) pq.poll();
        }
        List<String> out = new ArrayList<>(pq);
        out.sort((a, b) -> Integer.compare(freq.get(b), freq.get(a)));
        return out;
    }

    public synchronized List<String> topWords(int n) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(freq.entrySet());
        es.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, es.size()); i++) out.add(es.get(i).getKey());
        return out;
    }

    /** Optimal string alignment (Дамерау–Левенштейн с одной перестановкой). */
    public static int osa(String a, String b, int max) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > max) return max + 1;
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
            }
        }
        return d[n][m];
    }
}
