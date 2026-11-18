package dev.rut9.engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.rut9.config.Rut9Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Личная языковая модель игрока: частоты слов, биграммы, триграммы, игнорируемые слова. Хранится локально. */
public final class UserModel {
    public static final class Data {
        public Map<String, Integer> uni = new HashMap<>();
        public Map<String, Map<String, Integer>> bi = new HashMap<>();
        public Map<String, Map<String, Integer>> tri = new HashMap<>();
        public List<String> ignored = new ArrayList<>();
    }

    private static final Gson GSON = new GsonBuilder().create();

    private final Map<String, Integer> uni = new HashMap<>();
    private final NextTable bi = new NextTable();
    private final NextTable tri = new NextTable();
    private final Set<String> ignored = new HashSet<>();
    private volatile boolean dirty;

    public NextTable bi() { return bi; }
    public NextTable tri() { return tri; }

    private static Path file() {
        return Rut9Config.dir().resolve("user_model.json");
    }

    public synchronized int count(String w) {
        return uni.getOrDefault(w, 0);
    }

    /** Слово считается «своим» (не исправляется), если игрок использовал его ≥2 раз или отменил его исправление. */
    public synchronized boolean isKnown(String w) {
        return ignored.contains(w) || uni.getOrDefault(w, 0) >= 2;
    }

    public synchronized void ignore(String w) {
        w = w.toLowerCase(Locale.ROOT).replace('ё', 'е');
        ignored.add(w);
        uni.merge(w, 2, Integer::sum);
        dirty = true;
    }

    public synchronized List<String> prefixWords(String p) {
        List<String> out = new ArrayList<>();
        for (String w : uni.keySet()) {
            if (w.startsWith(p)) {
                out.add(w);
                if (out.size() >= 30) break;
            }
        }
        return out;
    }

    public synchronized List<String> frequentWords(int min) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : uni.entrySet()) {
            if (e.getValue() >= min && Tx.isCyrillicWord(e.getKey())) out.add(e.getKey());
        }
        return out;
    }

    /** Обучение на отправленном сообщении (слова уже в нижнем регистре). */
    public synchronized void learn(List<String> words) {
        String p2 = null, p1 = "<s>";
        for (String w : words) {
            if (w.length() > 40) continue;
            uni.merge(w, 1, Integer::sum);
            bi.add(p1, w, 1);
            if (p2 != null) tri.add(p2 + " " + p1, w, 1);
            p2 = p1;
            p1 = w;
        }
        if (uni.size() > 50_000) uni.values().removeIf(v -> v <= 1);
        dirty = true;
    }

    /** Игрок выбрал вариант из панели — усиленный вес. */
    public synchronized void recordPick(String head, String sug) {
        List<String> ws = Tx.tokenize(Tx.sentenceOf(head));
        String p1 = ws.isEmpty() ? "<s>" : ws.get(ws.size() - 1);
        String p2 = ws.size() >= 2 ? ws.get(ws.size() - 2) : (ws.size() == 1 ? "<s>" : null);
        String s = sug.toLowerCase(Locale.ROOT).replace('ё', 'е');
        bi.add(p1, s, 2);
        if (p2 != null) tri.add(p2 + " " + p1, s, 2);
        if (s.indexOf(' ') < 0) uni.merge(s, 2, Integer::sum);
        dirty = true;
    }

    public synchronized void load() {
        Path p = file();
        if (!Files.exists(p)) return;
        try {
            Data d = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Data.class);
            if (d == null) return;
            if (d.uni != null) uni.putAll(d.uni);
            bi.putAll(d.bi);
            tri.putAll(d.tri);
            if (d.ignored != null) ignored.addAll(d.ignored);
        } catch (Exception ignoredEx) {
        }
    }

    public synchronized void save() {
        if (!dirty) return;
        Data d = new Data();
        d.uni = new HashMap<>(uni);
        d.bi = bi.snapshot();
        d.tri = tri.snapshot();
        d.ignored = new ArrayList<>(ignored);
        try {
            Files.createDirectories(file().getParent());
            Path tmp = file().resolveSibling("user_model.json.tmp");
            Files.writeString(tmp, GSON.toJson(d), StandardCharsets.UTF_8);
            Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING);
            dirty = false;
        } catch (IOException ignoredEx) {
        }
    }

    public synchronized void reset() {
        uni.clear();
        bi.clear();
        tri.clear();
        ignored.clear();
        dirty = true;
        save();
    }
}
