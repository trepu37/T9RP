package dev.rut9.engine;

import dev.rut9.config.Rut9Config;
import dev.rut9.rp.RpEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Фасад всей языковой системы. Загрузка и предсказание — в фоновом потоке, исправление — мгновенное, синхронное. */
public final class T9Engine {
    private static final Logger LOG = LoggerFactory.getLogger("rut9");
    private static final String RES = "/assets/rut9/dict/";

    private final Rut9Config cfg;
    private final Lexicon lex = new Lexicon();
    private final NextTable sBi = new NextTable();
    private final NextTable sTri = new NextTable();
    private final UserModel user = new UserModel();
    private final Topics topics = new Topics();
    private final Corrector corrector = new Corrector(lex, user, sBi);
    private final Predictor predictor = new Predictor(lex, sBi, sTri, user, topics);

    private final RpEngine rp;

    private volatile boolean ready;
    private volatile List<String> suggestions = List.of();
    private final AtomicInteger version = new AtomicInteger();
    private int learnedSinceSave;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rut9-engine");
        t.setDaemon(true);
        return t;
    });

    public T9Engine(Rut9Config cfg) {
        this.cfg = cfg;
        this.rp = new RpEngine(cfg, lex, corrector, predictor);
    }

    /** РП-движок (/me /do /todo /try): граф связок, форматтер, LLM. */
    public RpEngine rp() { return rp; }

    public void initAsync() {
        exec.submit(this::load);
    }

    private static BufferedReader res(String name) {
        InputStream in = T9Engine.class.getResourceAsStream(RES + name);
        return in == null ? null : new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private void load() {
        long t0 = System.currentTimeMillis();
        try {
            try (BufferedReader r = res("words.txt")) { if (r != null) lex.load(r); }
            if (cfg.bigDictionary) {
                try (BufferedReader r = res("words_big.txt")) {
                    if (r != null) lex.loadBig(r, cfg.dictMaxWords, cfg.dictIndexWords);
                }
            }
            Path extra = Rut9Config.dir().resolve("dictionary.txt");
            if (Files.exists(extra)) try (BufferedReader r = Files.newBufferedReader(extra)) { lex.load(r); }

            try (BufferedReader r = res("bigrams.txt")) { if (r != null) loadNgrams(r, sBi, 2); }
            try (BufferedReader r = res("trigrams.txt")) { if (r != null) loadNgrams(r, sTri, 3); }
            Path extraBi = Rut9Config.dir().resolve("bigrams.txt");
            if (Files.exists(extraBi)) try (BufferedReader r = Files.newBufferedReader(extraBi)) { loadNgrams(r, sBi, 2); }
            try (BufferedReader r = res("topics.txt")) { if (r != null) topics.load(r); }

            user.load();
            for (String w : user.frequentWords(2)) if (!lex.contains(w)) lex.add(w, 800);
            predictor.warm();
            rp.load(); // после warm(): РП-слова не должны попасть в топ обычных подсказок
            ready = true;
            LOG.info("[rut9] словарь загружен: {} слов за {} мс", lex.size(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            LOG.error("[rut9] ошибка загрузки словарей", e);
        }
    }

    /** Формат: «w1 w2 [w3] count». */
    private static void loadNgrams(BufferedReader r, NextTable t, int n) throws IOException {
        String line;
        while ((line = r.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            if (p.length != n + 1 || !p[n].matches("\\d+")) continue;
            String key = n == 2 ? p[0] : p[0] + " " + p[1];
            t.add(key, p[n - 1], Integer.parseInt(p[n]));
        }
    }

    public boolean ready() { return ready; }

    public List<String> suggestions() { return suggestions; }

    public String correct(String word, String prev, boolean sentenceStart) {
        return ready ? corrector.correct(word, prev, sentenceStart) : null;
    }

    public String fixLayout(String word) {
        return ready ? LayoutFixer.fix(word, lex) : null;
    }

    public String punctuate(String text) {
        return Punctuator.apply(text, cfg.addPeriod);
    }

    public void ignore(String word) {
        user.ignore(word);
    }

    public void recordPick(String head, String sug) {
        if (cfg.learning) user.recordPick(head, sug);
    }

    public void learn(String text) {
        if (!cfg.learning || text.startsWith("/")) return;
        List<String> ws = Tx.tokenize(text);
        user.learn(ws);
        for (String w : ws) if (Tx.isCyrillicWord(w) && user.count(w) >= 2 && !lex.contains(w)) lex.add(w, 800);
        if (++learnedSinceSave >= 5) {
            learnedSinceSave = 0;
            exec.submit(user::save);
        }
    }

    /** Асинхронно пересчитать панель; устаревшие запросы отбрасываются. */
    public void requestSuggestions(String text) {
        if (!ready || !cfg.predictions) {
            suggestions = List.of();
            return;
        }
        int v = version.incrementAndGet();
        exec.submit(() -> {
            if (v != version.get()) return;
            try {
                List<String> r = predictor.predict(text, 3);
                if (v == version.get()) suggestions = r;
            } catch (Exception e) {
                LOG.warn("[rut9] ошибка предсказания", e);
            }
        });
    }

    public void clearSuggestions() {
        version.incrementAndGet();
        suggestions = List.of();
    }

    public void saveNow() { user.save(); }

    public void resetUser() { user.reset(); }

    public void shutdown() {
        user.save();
        rp.shutdown();
        exec.shutdown();
    }
}
