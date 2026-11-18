package dev.rut9.rp;

import dev.rut9.config.Rut9Config;
import dev.rut9.engine.Corrector;
import dev.rut9.engine.Lexicon;
import dev.rut9.engine.Predictor;
import dev.rut9.engine.Tx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * «Мозги» РП-Т9. Два уровня:
 * 1) локальный граф связок (мгновенно, офлайн): правила из chains.json + память чата + память своих /me;
 * 2) опциональный LLM: после паузы в наборе подменяет локальные варианты своими тремя.
 * Если LLM не настроен/недоступен — всё остаётся на локальном уровне, без ошибок в интерфейсе.
 */
public final class RpEngine {
    private static final Logger LOG = LoggerFactory.getLogger("rut9");
    private static final String RES = "/assets/rut9/rp/";
    private static final int RP_WORD_FREQ = 6000;
    private static final int MAX_LEN = 256; // лимит ванильного поля чата

    /** text — «чистый» вариант (для обучения), insertion — что реально дописывается к строке. */
    public record Cand(String text, String insertion) {}

    private static final Set<String> CONNECT_END = Set.of("и", "а", "но", "затем", "потом", "после", "чего", "чтобы",
            "пока", "когда", "тогда", "либо", "или", "да");
    private static final Set<String> CONNECT_TYPED = Set.of("и", "а", "затем", "потом");
    private static final Set<String> PREPS = Set.of("в", "на", "к", "из", "с", "со", "по", "у", "от", "до", "за", "под",
            "над", "о", "об", "при", "для", "без", "перед");
    private static final String[] TAKE_STEMS = {"достал", "вытащ", "вынул", "выхват", "взял", "схват", "поднял", "извлек"};

    private final Rut9Config cfg;
    private final Lexicon lex;
    private final Predictor predictor;
    private final RpData data = new RpData();
    private final ContextGraph graph = new ContextGraph();
    private final RpFormatter fmt;
    private final ChatContext ctx = new ChatContext();
    private final LlmClient llm;

    private final Map<String, Integer> picks = new HashMap<>();
    private int picksSinceSave;

    private volatile boolean ready;
    private volatile List<Cand> cur = List.of();
    private volatile int sel;
    private volatile boolean llmBusy;
    private final AtomicInteger version = new AtomicInteger();
    private volatile ScheduledFuture<?> pending;

    private final Map<String, List<Cand>> llmCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Cand>> e) { return size() > 40; }
    };

    private final ScheduledExecutorService sched = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "rut9-rp");
        t.setDaemon(true);
        return t;
    });

    public RpEngine(Rut9Config cfg, Lexicon lex, Corrector corr, Predictor predictor) {
        this.cfg = cfg;
        this.lex = lex;
        this.predictor = predictor;
        this.fmt = new RpFormatter(data, lex, corr, cfg);
        this.llm = new LlmClient(cfg);
    }

    // ================================================================== загрузка

    private static BufferedReader res(String name) {
        InputStream in = RpEngine.class.getResourceAsStream(RES + name);
        return in == null ? null : new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static BufferedReader file(String name) throws java.io.IOException {
        Path p = Rut9Config.dir().resolve(name);
        return Files.exists(p) ? Files.newBufferedReader(p, StandardCharsets.UTF_8) : null;
    }

    private static String slurp(BufferedReader r) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        return sb.toString();
    }

    /** Загрузка/перезагрузка словарей и графа (вызывается из фонового потока T9Engine и из /t9 reload). */
    public void load() {
        try {
            graph.clear();
            try (BufferedReader r = res("verbs.txt")) { if (r != null) data.loadVerbs(r); }
            try (BufferedReader r = res("nouns.txt")) { if (r != null) data.loadNouns(r); }
            try (BufferedReader r = res("words.txt")) { if (r != null) data.loadWords(r); }
            try (BufferedReader r = file("rp_verbs.txt")) { if (r != null) data.loadVerbs(r); }
            try (BufferedReader r = file("rp_nouns.txt")) { if (r != null) data.loadNouns(r); }
            try (BufferedReader r = file("rp_words.txt")) { if (r != null) data.loadWords(r); }
            for (String w : data.vocab()) lex.add(w, RP_WORD_FREQ);

            try (BufferedReader r = res("chains.json")) { if (r != null) graph.load(slurp(r)); }
            try (BufferedReader r = file("chains.json")) { if (r != null) graph.load(slurp(r)); }
            loadPicks();
            ready = true;
            LOG.info("[rut9] РП-движок: {} правил /me, {} /do, {} речевых, {} слов", graph.me.size(),
                    graph.doRules.size(), graph.speech.size(), data.vocab().size());
        } catch (Exception e) {
            LOG.error("[rut9] ошибка загрузки РП-данных (проверьте config/rut9/chains.json)", e);
        }
    }

    private Path picksFile() { return Rut9Config.dir().resolve("rp_picks.tsv"); }

    private void loadPicks() {
        Path p = picksFile();
        if (!Files.exists(p)) return;
        try {
            synchronized (picks) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    int t = line.indexOf('\t');
                    if (t > 0) picks.merge(line.substring(t + 1), Integer.parseInt(line.substring(0, t)), Integer::sum);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void savePicks() {
        try {
            StringBuilder sb = new StringBuilder();
            synchronized (picks) {
                if (picks.size() > 2000) picks.values().removeIf(v -> v <= 1);
                for (Map.Entry<String, Integer> e : picks.entrySet()) sb.append(e.getValue()).append('\t').append(e.getKey()).append('\n');
            }
            Files.createDirectories(picksFile().getParent());
            Files.writeString(picksFile(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
    }

    public void shutdown() {
        if (cfg.rpLearn) savePicks();
        sched.shutdownNow();
    }

    // ================================================================== простой API для интерфейса

    public boolean ready() { return ready; }
    public ChatContext ctx() { return ctx; }
    public boolean llmConfigured() { return llm.configured(); }
    public boolean llmAvailable() { return llm.available(); }
    public boolean llmPending() { return llmBusy; }

    public List<String> variants() {
        List<Cand> c = cur;
        List<String> out = new ArrayList<>(c.size());
        for (Cand x : c) out.add(x.insertion());
        return out;
    }

    public int selected() { return Math.min(sel, Math.max(0, cur.size() - 1)); }

    public void cycle(int delta) {
        int n = cur.size();
        if (n > 0) sel = ((selected() + delta) % n + n) % n;
    }

    /** Принять вариант: вернуть текст для дописывания и запомнить выбор. */
    public String accept(int idx) {
        List<Cand> c = cur;
        if (idx < 0 || idx >= c.size()) return null;
        Cand x = c.get(idx);
        if (cfg.rpLearn) {
            synchronized (picks) { picks.merge(x.text(), 1, Integer::sum); }
            if (++picksSinceSave >= 10) {
                picksSinceSave = 0;
                sched.execute(this::savePicks);
            }
        }
        return x.insertion();
    }

    public void clear() {
        version.incrementAndGet();
        ScheduledFuture<?> p = pending;
        if (p != null) p.cancel(false);
        llmBusy = false;
        cur = List.of();
        sel = 0;
    }

    public String formatLine(String line) { return fmt.formatLine(line); }

    public String fixWordLive(String word, String headBody) { return fmt.fixWordLive(word, headBody); }

    // ================================================================== подсказки

    /** Вызывать при каждом изменении текста в РП-строке (клиентский поток; локальный расчёт — доли миллисекунды). */
    public void update(RpCommand cmd, String body) {
        int v = version.incrementAndGet();
        ScheduledFuture<?> p = pending;
        if (p != null) p.cancel(false);
        sel = 0;
        if (!ready) { cur = List.of(); llmBusy = false; return; }
        List<Cand> local;
        try {
            local = compute(cmd, body);
        } catch (RuntimeException e) {
            LOG.warn("[rut9] ошибка РП-подсказок", e);
            local = List.of();
        }
        cur = local;
        if (llm.available()) {
            llmBusy = true;
            final List<Cand> fallback = local;
            pending = sched.schedule(() -> runLlm(cmd, body, v, fallback), Math.max(0, cfg.llmDebounceMs), TimeUnit.MILLISECONDS);
        } else {
            llmBusy = false;
        }
    }

    private record Split(boolean speech, String act, String done, String partial) {}

    private static Split split(RpCommand cmd, String body) {
        boolean speech = false;
        String act = body;
        if (cmd == RpCommand.TODO) {
            int st = body.indexOf('*');
            if (st < 0) speech = true; else act = body.substring(st + 1);
        }
        String partial = Tx.trailingWord(act);
        return new Split(speech, act, act.substring(0, act.length() - partial.length()), partial);
    }

    private List<Cand> compute(RpCommand cmd, String body) {
        Split s = split(cmd, body);
        List<String> toks = Tx.tokenize(s.done());
        List<String> chat = ctx.recentLines(Math.max(5, Math.min(10, cfg.rpChatLines)));
        List<ChatContext.Own> own = ctx.recentOwn(3);

        List<Map.Entry<String, Double>> ranked = rank(cmd, s.speech(), toks, chat, own);

        String actNorm = Tok.norm(s.act());
        boolean connector = !s.partial().isEmpty() && CONNECT_TYPED.contains(Tok.norm(s.partial()));
        List<Cand> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // режимы: 0 — набранная «и»/«затем» без пробела; 1 — дописываем начатое слово;
        //         2 — слово уже набрано целиком (оно в словаре) — предлагаем продолжение после него
        boolean wholeWord = !s.partial().isEmpty() && lex.contains(s.partial());
        Split whole = new Split(s.speech(), s.act(), s.act(), "");
        for (int mode = connector ? 0 : 1; mode <= (wholeWord ? 2 : 1) && out.size() < 3; mode++) {
            List<Map.Entry<String, Double>> rk = ranked;
            if (mode == 2) rk = rank(cmd, s.speech(), Tx.tokenize(s.act()), chat, own); // слово уже полное — учитываем его
            for (Map.Entry<String, Double> e : rk) {
                String text = e.getKey();
                if (actNorm.contains(Tok.norm(text))) continue; // это уже написано
                String shown = (cmd == RpCommand.TODO && !s.speech()) ? fmt.gerundize(text, toks.isEmpty()) : text;
                String ins = mode == 2 ? insertion(cmd, whole, shown, false) : insertion(cmd, s, shown, mode == 0);
                if (ins == null || ins.isBlank() || !seen.add(ins)) continue;
                out.add(new Cand(text, ins));
                if (out.size() >= 3) break;
            }
        }
        // запасной вариант: дописать текущее слово по словарю/n-граммам
        if (out.size() < 3 && s.partial().length() >= 2 && !connector) addWordCompletions(s, out, seen);
        return fit(body, out);
    }

    private List<Map.Entry<String, Double>> rank(RpCommand cmd, boolean speech, List<String> toks, List<String> chat,
                                                 List<ChatContext.Own> own) {
        Map<String, Double> sc = new HashMap<>();
        if (speech) scoreSpeech(toks, chat, own, sc);
        else if (cmd == RpCommand.DO) scoreDo(toks, chat, own, sc);
        else scoreMe(toks, chat, own, sc);
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(sc.entrySet());
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return ranked;
    }

    /** Не выходить за лимит ванильного поля чата. */
    private static List<Cand> fit(String body, List<Cand> in) {
        // длина всей строки неизвестна здесь точно (префикс «/todo »), берём с запасом 8 символов
        int room = MAX_LEN - body.length() - 8;
        List<Cand> out = new ArrayList<>();
        for (Cand c : in) {
            if (c.insertion().length() <= room) out.add(c);
            else if (room > 12) {
                int cut = c.insertion().lastIndexOf(' ', room);
                if (cut > 4) out.add(new Cand(c.text(), c.insertion().substring(0, cut)));
            }
        }
        return out;
    }

    private void addWordCompletions(Split s, List<Cand> out, Set<String> seen) {
        try {
            String lp = Tok.norm(s.partial());
            for (String p : predictor.predict(s.act(), 6)) {
                String lw = Tok.norm(p);
                if (!lw.startsWith(lp) || lw.length() <= lp.length()) continue;
                String ins = p.substring(s.partial().length());
                if (seen.add(ins)) out.add(new Cand(p, ins));
                if (out.size() >= 3) break;
            }
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Превращает «чистый» вариант в строку для дописывания: достраивает слово, добавляет «и»/пробел.
     * @param connectorMode игрок уже набрал «и»/«затем» без пробела — тогда partial сам является связкой
     */
    private String insertion(RpCommand cmd, Split s, String text, boolean connectorMode) {
        String act = s.act(), done = s.done(), partial = s.partial();
        if (connectorMode) return " " + text;
        if (!partial.isEmpty()) {
            return Tok.norm(text).startsWith(Tok.norm(partial)) ? text.substring(partial.length()) : null;
        }
        if (cmd == RpCommand.DO || s.speech()) {
            if (done.isBlank()) return text;
            String t = done.stripTrailing();
            if (".!?…".indexOf(t.charAt(t.length() - 1)) < 0) return null; // предложение ещё не закончено
            return (done.endsWith(" ") ? "" : " ") + text;
        }
        if (done.isBlank()) return text;
        String t = done.stripTrailing();
        String sp = done.endsWith(" ") ? "" : " ";
        String lw = Tok.norm(Tx.trailingWord(t));
        if (t.endsWith(",") || t.endsWith(":") || CONNECT_END.contains(lw)) return sp + text;
        if (PREPS.contains(lw)) return null; // оборвано на предлоге — продолжение «и …» было бы ошибкой
        return sp + "и " + text;
    }

    // ------------------------------------------------------------------ оценка правил

    private static int lastMatch(List<String> toks, List<String> stems) {
        for (int i = toks.size() - 1; i >= 0; i--) {
            for (String st : stems) if (toks.get(i).startsWith(st)) return i;
        }
        return -1;
    }

    private static boolean containsAny(String hay, List<String> stems) {
        for (String st : stems) if (hay.contains(st)) return true;
        return false;
    }

    private double pick(String text) {
        synchronized (picks) { return 0.8 * Math.log1p(picks.getOrDefault(text, 0)); }
    }

    /** Буст по темам: тема варианта (doc, money…) встречается в последних строках чата / своих действиях. */
    private double tagBoost(Set<String> vtags, List<String> chat, List<ChatContext.Own> own) {
        double sum = 0;
        for (String tag : vtags) {
            List<String> stems = graph.tags.get(tag);
            if (stems == null) continue;
            double best = 0;
            for (int k = 0; k < chat.size(); k++) {
                if (containsAny(chat.get(k), stems)) { best = 3.0 - 0.25 * k; break; } // свежее — важнее
            }
            for (int k = 0; k < own.size(); k++) {
                if (containsAny(Tok.norm(own.get(k).body()), stems)) { best = Math.max(best, 2.0 - 0.4 * k); break; }
            }
            sum += Math.max(0, best);
        }
        return sum;
    }

    private static void add(Map<String, Double> sc, String text, double score) { sc.merge(text, score, Math::max); }

    private void scoreMe(List<String> toks, List<String> chat, List<ChatContext.Own> own, Map<String, Double> sc) {
        for (ContextGraph.Rule r : graph.me) {
            double base, pos = 0;
            if (r.when.isEmpty()) {
                if (!toks.isEmpty()) continue; // «стартовые» правила только для пустого начала
                base = 0;
            } else {
                int idx = lastMatch(toks, r.when);
                if (idx < 0) continue;
                if (!r.and.isEmpty() && lastMatch(toks, r.and) < 0) continue;
                if (!r.unless.isEmpty() && lastMatch(toks, r.unless) >= 0) continue;
                base = 1 + (r.and.isEmpty() ? 0 : 1);
                pos = 2.0 * (idx + 1) / toks.size(); // упоминание ближе к концу — актуальнее
            }
            for (ContextGraph.Variant v : r.next) {
                add(sc, v.text(), r.w * v.w() + base + pos + tagBoost(v.tags(), chat, own) + pick(v.text()));
            }
        }
    }

    private int ownMatch(List<ChatContext.Own> own, List<String> stems) {
        for (int k = 0; k < own.size(); k++) {
            if (own.get(k).cmd() == RpCommand.DO) continue;
            if (containsAny(Tok.norm(own.get(k).body()), stems)) return k;
        }
        return -1;
    }

    private static int chatMatch(List<String> chat, List<String> stems) {
        for (int k = 0; k < chat.size(); k++) if (containsAny(chat.get(k), stems)) return k;
        return -1;
    }

    private void scoreDo(List<String> toks, List<String> chat, List<ChatContext.Own> own, Map<String, Double> sc) {
        for (ContextGraph.Rule r : graph.doRules) {
            double base = 0;
            if (r.afterMe.isEmpty() && r.chat.isEmpty()) {
                if (!toks.isEmpty()) continue;
                base = 0.2;
            } else {
                boolean fired = false;
                if (!r.afterMe.isEmpty()) {
                    int k = ownMatch(own, r.afterMe);
                    if (k >= 0) { base += 3.0 - 0.6 * k; fired = true; }
                }
                if (!r.chat.isEmpty()) {
                    int k = chatMatch(chat, r.chat);
                    if (k >= 0) { base += 1.5 - 0.1 * k; fired = true; }
                }
                if (!fired) continue;
            }
            for (ContextGraph.Variant v : r.next) {
                add(sc, v.text(), r.w * v.w() + base + tagBoost(v.tags(), chat, own) + pick(v.text()));
            }
        }
        // общий случай: «достал <предмет>» -> «<Предмет> находится в правой руке.»
        for (int k = 0; k < own.size(); k++) {
            ChatContext.Own o = own.get(k);
            if (o.cmd() == RpCommand.DO) continue;
            String nb = Tok.norm(o.body());
            boolean take = false;
            for (String st : TAKE_STEMS) if (nb.contains(st)) { take = true; break; }
            if (!take) continue;
            for (String t : Tx.tokenize(o.body())) {
                RpData.Noun n = data.noun(t);
                if (n != null && n.has("item") && data.casesOf(t).contains(RpData.ACC)) {
                    String nom = n.nom();
                    String text = Character.toUpperCase(nom.charAt(0)) + nom.substring(1)
                            + (n.has("pl") ? " находятся в правой руке." : " находится в правой руке.");
                    add(sc, text, 2.2 - 0.5 * k);
                    break;
                }
            }
            break; // только самое свежее «достал …»
        }
    }

    private void scoreSpeech(List<String> toks, List<String> chat, List<ChatContext.Own> own, Map<String, Double> sc) {
        for (ContextGraph.Rule r : graph.speech) {
            double base = 0;
            if (r.chat.isEmpty()) {
                if (!toks.isEmpty()) continue;
                base = 0.2;
            } else {
                int k = chatMatch(chat, r.chat);
                if (k < 0) continue;
                base = 2.0 - 0.2 * k;
            }
            for (ContextGraph.Variant v : r.next) add(sc, v.text(), r.w * v.w() + base + pick(v.text()));
        }
    }

    // ================================================================== LLM

    private void runLlm(RpCommand cmd, String body, int v, List<Cand> fallback) {
        try {
            if (v != version.get()) return;
            Split s = split(cmd, body);
            List<String> chatRaw = ctx.recentLinesRaw(Math.max(1, Math.min(10, cfg.llmChatLines)));
            List<ChatContext.Own> own = ctx.recentOwn(3);
            List<String> ownLines = new ArrayList<>();
            for (ChatContext.Own o : own) ownLines.add("/" + o.cmd().word + " " + o.body());

            String key = cmd + "\0" + body + "\0" + chatRaw.hashCode() + "\0" + ownLines.hashCode();
            List<Cand> res;
            synchronized (llmCache) { res = llmCache.get(key); }
            if (res == null) {
                List<String> raw = llm.continuations(cmd, s.act().isEmpty() ? "" : s.act(), s.speech(), chatRaw, ownLines);
                res = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (String r : raw) {
                    String text = cleanLlm(cmd, s, r);
                    if (text.isBlank()) continue;
                    String ins = insertion(cmd, s, text, false);
                    if (ins == null || ins.isBlank() || !seen.add(ins)) continue;
                    res.add(new Cand(text, ins));
                }
                res = fit(body, res);
                synchronized (llmCache) { llmCache.put(key, res); }
            }
            if (v != version.get()) return;
            if (!res.isEmpty()) {
                List<Cand> merged = new ArrayList<>(res);
                Set<String> seen = new HashSet<>();
                for (Cand c : merged) seen.add(c.insertion());
                for (Cand c : fallback) if (merged.size() < 3 && seen.add(c.insertion())) merged.add(c);
                cur = merged;
                sel = 0;
            }
        } catch (Exception e) {
            LOG.debug("[rut9] LLM недоступна, остаёмся на локальном движке: {}", e.toString());
        } finally {
            if (v == version.get()) llmBusy = false;
        }
    }

    /** Привести ответ модели к правилам команды (строчная/заглавная, точка, деепричастие). */
    private String cleanLlm(RpCommand cmd, Split s, String raw) {
        String t = raw.strip();
        for (String q : new String[]{"«»", "\"\"", "''"}) {
            if (t.length() > 1 && t.charAt(0) == q.charAt(0) && t.charAt(t.length() - 1) == q.charAt(1)) t = t.substring(1, t.length() - 1).strip();
        }
        RpCommand.Parsed echoed = RpCommand.parse(t);
        if (echoed != null) t = echoed.body().strip();
        String typed = s.act().strip();
        if (!typed.isEmpty() && Tok.norm(t).startsWith(Tok.norm(typed)) && t.length() > typed.length()) {
            t = t.substring(typed.length()).strip(); // модель повторила уже введённое
            if (!s.partial().isEmpty()) t = s.partial() + t; // но обрыв слова нужно сохранить
        }
        if (s.speech()) return RpFormatter.capitalizeFirst(t);
        if (cmd == RpCommand.DO) {
            if (s.done().isBlank() || ".!?…".indexOf(s.done().stripTrailing().charAt(s.done().stripTrailing().length() - 1)) >= 0) {
                t = RpFormatter.capitalizeFirst(t);
            }
            char last = t.isEmpty() ? '.' : t.charAt(t.length() - 1);
            return (last == '.' || last == '!' || last == '?') ? t : t + ".";
        }
        t = RpFormatter.lowerFirst(RpFormatter.stripTrailingDots(t));
        if (cmd == RpCommand.TODO) t = fmt.gerundize(t, s.done().isBlank());
        return t;
    }

    // ------------------------------------------------------------------ «причесать» (горячая клавиша)

    /**
     * Литературная правка через LLM. cb вызывается из фонового потока; результат уже приведён к правилам РП.
     * Вызывать только если {@link #llmAvailable()}.
     */
    public void polishLlm(String line, Consumer<String> cb) {
        RpCommand.Parsed p = RpCommand.parse(line);
        if (p == null || p.body().isBlank()) return;
        sched.execute(() -> {
            try {
                String out = llm.polish(p.cmd(), p.body(), ctx.recentLinesRaw(Math.max(1, Math.min(10, cfg.llmChatLines))));
                if (out.isBlank()) return;
                cb.accept(fmt.formatLine(p.prefix() + out, false));
            } catch (Exception e) {
                LOG.debug("[rut9] LLM-правка не удалась: {}", e.toString());
            }
        });
    }

    /** Для /t9 llmtest: блокирующая проверка соединения (звать из фонового потока). */
    public String llmPing() throws Exception {
        return llm.ping();
    }

    public String llmEndpoint() { return llm.endpoint(); }

    public void runAsync(Runnable r) { sched.execute(r); }
}
