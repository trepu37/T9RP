package dev.rut9.engine;

import java.util.List;
import java.util.Locale;

/** Исправление слов: словарь + расстояние Дамерау–Левенштейна + модель опечаток + контекст (предыдущее слово). */
public final class Corrector {
    private final Lexicon lex;
    private final UserModel user;
    private final NextTable staticBi;

    public Corrector(Lexicon lex, UserModel user, NextTable staticBi) {
        this.lex = lex;
        this.user = user;
        this.staticBi = staticBi;
    }

    /** @return исправленное слово (с сохранением регистра) или null, если исправлять не нужно. */
    public String correct(String word, String prev, boolean sentenceStart) {
        String lower = word.toLowerCase(Locale.ROOT).replace('ё', 'е');
        if (lower.length() < 2 || !Tx.isCyrillicWord(lower)) return null;
        if (lower.indexOf('-') >= 0) return null;
        if (lex.contains(lower) || user.isKnown(lower)) return null;
        // Слово с заглавной буквы не в начале предложения — скорее имя/ник.
        if (!sentenceStart && Character.isUpperCase(word.charAt(0))) return null;

        int maxDist = lower.length() <= 4 ? 1 : 2;
        List<Lexicon.Cand> cs = lex.lookup(lower, maxDist);
        if (cs.isEmpty()) return null;

        String best = null;
        double bestScore = -1e9;
        for (Lexicon.Cand c : cs) {
            String w = c.word();
            double s = Math.log1p(lex.freq(w))
                    - 4.0 * c.dist()
                    + (w.charAt(0) == lower.charAt(0) ? 1.5 : 0)
                    + Keyboard.bonus(lower, w)
                    + 1.5 * Math.log1p(staticBi.get(prev, w) + user.bi().get(prev, w))
                    + 0.7 * Math.log1p(user.count(w))
                    + Morphology.bonus(prev, w);
            if (s > bestScore) {
                bestScore = s;
                best = w;
            }
        }
        if (best == null || best.equals(lower)) return null;
        return Tx.matchCase(word, best);
    }
}
