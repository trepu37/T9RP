package dev.rut9.rp;

import dev.rut9.config.Rut9Config;
import dev.rut9.engine.Corrector;
import dev.rut9.engine.LayoutFixer;
import dev.rut9.engine.Lexicon;
import dev.rut9.engine.Tx;

import java.util.List;

/**
 * Приведение РП-строки к строгому формату сервера.
 * <ul>
 *   <li>/me, /try — первая буква строчная, точки в конце нет;</li>
 *   <li>/do — первая буква заглавная, в конце точка (если не «?» / «!»);</li>
 *   <li>/todo Фраза*действие — фраза с заглавной, после «*» деепричастный оборот со строчной, без точки.</li>
 * </ul>
 * Перед этим: исправление раскладки, орфографии, предлогов, падежей и запятых.
 */
public final class RpFormatter {
    private final RpData data;
    private final Lexicon lex;
    private final Corrector corr;
    private final Rut9Config cfg;

    public RpFormatter(RpData data, Lexicon lex, Corrector corr, Rut9Config cfg) {
        this.data = data;
        this.lex = lex;
        this.corr = corr;
        this.cfg = cfg;
    }

    // ------------------------------------------------------------------ публичный API

    /** «/me засуну карман пиджака достал паспорт» -> «/me засунул руку в карман пиджака, достал паспорт». */
    public String formatLine(String line) { return formatLine(line, true); }

    /** @param full false — только регистр, точки и деепричастия (для текста от LLM, который уже грамотен). */
    public String formatLine(String line, boolean full) {
        RpCommand.Parsed p = RpCommand.parse(line);
        if (p == null || p.body().isBlank()) return line;
        String b = formatBody(p.cmd(), p.body(), full);
        return b.isEmpty() ? line : p.prefix() + b;
    }

    public String formatBody(RpCommand cmd, String body) { return formatBody(cmd, body, true); }

    public String formatBody(RpCommand cmd, String body, boolean full) {
        body = body.strip().replaceAll("\\s+", " ");
        if (body.isEmpty()) return "";
        return switch (cmd) {
            case ME, TRY -> finishLower(clause(body, full));
            case DO -> finishDo(clause(body, full));
            case TODO -> formatTodo(body, full);
        };
    }

    /** Исправление одного слова на лету (при вводе пробела). null — менять нечего. */
    public String fixWordLive(String word, String headBody) {
        String base = word;
        if (cfg.rpLayoutFix && !Tx.isCyrillicWord(word)) {
            String lf = LayoutFixer.fix(word, lex);
            if (lf != null) base = Tx.matchCase(word, lf);
        }
        if (!Tx.isCyrillicWord(base)) return null;
        if (cfg.rpSpellcheck) {
            List<String> ws = Tx.tokenize(headBody);
            String prev = ws.isEmpty() ? "<s>" : ws.get(ws.size() - 1);
            String c = safeCorrect(base, prev, ws.isEmpty());
            if (c != null) base = c;
        }
        return base.equals(word) ? null : base;
    }

    /** Преобразовать текст продолжения в деепричастный оборот (для /todo после «*»). */
    public String gerundize(String text, boolean firstOfAction) {
        List<Tok> tk = Tok.split(text);
        gerundize(tk, firstOfAction);
        return Tok.join(tk);
    }

    // ------------------------------------------------------------------ /todo

    private String formatTodo(String body, boolean full) {
        int star = body.indexOf('*');
        String speech = star < 0 ? body : body.substring(0, star);
        String action = star < 0 ? null : body.substring(star + 1);

        String sp = capitalizeFirst(clause(speech.strip(), full));
        if (action == null) return sp;

        action = action.strip();
        if (action.isEmpty()) return sp + "*";
        List<Tok> tk = clauseTokens(action, full);
        gerundize(tk, true);
        return sp + "*" + finishLower(Tok.join(tk));
    }

    private void gerundize(List<Tok> tk, boolean firstOfAction) {
        boolean first = firstOfAction;
        for (Tok t : tk) {
            if (t.core.isEmpty()) continue;
            String g = GerundConverter.convert(t.n(), data, first);
            if (g != null) t.core = g;
            first = false;
        }
    }

    // ------------------------------------------------------------------ общий проход

    private String clause(String text, boolean full) {
        return Tok.join(clauseTokens(text, full));
    }

    private List<Tok> clauseTokens(String text, boolean full) {
        if (full && cfg.rpLayoutFix) text = RpLayout.fix(text, lex);
        List<Tok> tk = Tok.split(text);
        if (full && cfg.rpSpellcheck) {
            spell(tk);
            Grammar.apply(tk, data);
        }
        return tk;
    }

    private void spell(List<Tok> tk) {
        for (int i = 0; i < tk.size(); i++) {
            Tok t = tk.get(i);
            if (t.core.length() < 2 || !Tx.isCyrillicWord(t.core)) continue;
            String prev = i == 0 ? "<s>" : tk.get(i - 1).n();
            String c = safeCorrect(t.core, prev, i == 0);
            if (c != null) t.core = c;
        }
    }

    /** Исправление принимается, только если правка минимальна или результат — известное РП-слово. */
    public String safeCorrect(String word, String prev, boolean first) {
        String c = corr.correct(word, prev, first);
        if (c == null) return null;
        int d = Lexicon.osa(Tok.norm(word), Tok.norm(c), 2);
        // две правки допускаем только у длинных слов и только к известному РП-слову (иначе «держи» -> «двери»)
        return (d <= 1 || (word.length() >= 6 && data.vocab().contains(Tok.norm(c)))) ? c : null;
    }

    // ------------------------------------------------------------------ регистр и точки

    private static int firstLetter(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isLetter(s.charAt(i))) return i;
        return -1;
    }

    static String capitalizeFirst(String s) {
        int i = firstLetter(s);
        if (i < 0) return s;
        return s.substring(0, i) + Character.toUpperCase(s.charAt(i)) + s.substring(i + 1);
    }

    static String lowerFirst(String s) {
        int i = firstLetter(s);
        if (i < 0) return s;
        boolean abbreviation = i + 1 < s.length() && Character.isUpperCase(s.charAt(i + 1));
        if (abbreviation) return s;
        return s.substring(0, i) + Character.toLowerCase(s.charAt(i)) + s.substring(i + 1);
    }

    static String stripTrailingDots(String s) {
        s = s.stripTrailing();
        while (s.endsWith(".") || s.endsWith("…")) s = s.substring(0, s.length() - 1).stripTrailing();
        return s;
    }

    private static String finishLower(String s) { return lowerFirst(stripTrailingDots(s)); }

    private static String finishDo(String s) {
        s = capitalizeFirst(s.strip());
        if (s.isEmpty()) return s;
        char last = s.charAt(s.length() - 1);
        if (last == '?' || last == '!') return s;
        if (last == '.' || last == '…') {
            while (s.endsWith("..")) s = s.substring(0, s.length() - 1);
            return s;
        }
        return s + ".";
    }
}
