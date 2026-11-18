package dev.rut9.engine;

import java.util.*;

/**
 * Предсказание слов: n-граммная языковая модель (личная + встроенная) + частоты + морфология
 * + тематический слой. Учитывает всё текущее предложение (темы), а не только последнее слово.
 */
public final class Predictor {
    private final Lexicon lex;
    private final NextTable sBi, sTri;
    private final UserModel user;
    private final Topics topics;
    private volatile List<String> top = List.of();

    public Predictor(Lexicon lex, NextTable sBi, NextTable sTri, UserModel user, Topics topics) {
        this.lex = lex;
        this.sBi = sBi;
        this.sTri = sTri;
        this.user = user;
        this.topics = topics;
    }

    public void warm() {
        top = lex.topWords(300);
    }

    private static void addRow(Map<String, Double> sc, Map<String, Integer> row, double w, String pl) {
        for (Map.Entry<String, Integer> e : row.entrySet()) {
            String c = e.getKey();
            if (!pl.isEmpty() && !c.startsWith(pl)) continue;
            sc.merge(c, w * Math.log1p(e.getValue()), Double::sum);
        }
    }

    public List<String> predict(String text, int max) {
        String partial = Tx.trailingWord(text);
        String head = text.substring(0, text.length() - partial.length());
        List<String> ws = Tx.tokenize(Tx.sentenceOf(head));
        String p1 = ws.isEmpty() ? "<s>" : ws.get(ws.size() - 1);
        String p2 = ws.size() >= 2 ? ws.get(ws.size() - 2) : (ws.size() == 1 ? "<s>" : null);
        String triKey = p2 == null ? null : p2 + " " + p1;
        String pl = partial.toLowerCase(Locale.ROOT).replace('ё', 'е');
        boolean hasPartial = !pl.isEmpty();

        Map<String, Double> sc = new HashMap<>();
        addRow(sc, user.tri().row(triKey), 3.0, pl);
        addRow(sc, user.bi().row(p1), 2.5, pl);
        addRow(sc, sTri.row(triKey), 2.2, pl);
        addRow(sc, sBi.row(p1), 2.0, pl);

        for (String c : topics.suggest(ws, p1, p2)) {
            if (pl.isEmpty() || c.toLowerCase(Locale.ROOT).startsWith(pl)) sc.merge(c, 9.0, Double::sum);
        }

        if (hasPartial) {
            for (String w : lex.prefixSearch(pl, 60)) sc.putIfAbsent(w, 0.0);
            for (String w : user.prefixWords(pl)) sc.putIfAbsent(w, 0.0);
            if (sc.isEmpty()) { // нет слов с таким началом — вероятно, опечатка: предлагаем исправления
                for (Lexicon.Cand c : lex.lookup(pl, pl.length() <= 4 ? 1 : 2)) {
                    sc.merge(c.word(), -3.0 * c.dist(), Double::sum);
                }
            }
        } else if (sc.size() < 12) {
            for (String w : top) if (w.length() >= 2) sc.putIfAbsent(w, 0.0);
        }

        List<Map.Entry<String, Double>> ranked = new ArrayList<>();
        for (Map.Entry<String, Double> e : sc.entrySet()) {
            String c = e.getKey();
            if (c.isEmpty() || c.equals("<s>") || (hasPartial && c.equals(pl))) continue;
            double s = e.getValue()
                    + 0.35 * Math.log1p(lex.freq(c))
                    + 1.5 * Math.log1p(user.count(c))
                    + Morphology.bonus(p1, c);
            ranked.add(Map.entry(c, s));
        }
        ranked.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        boolean cap = (ws.isEmpty() && Tx.sentenceStart(head))
                || (hasPartial && Character.isUpperCase(partial.charAt(0)));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Double> e : ranked) {
            out.add(cap ? Tx.capitalize(e.getKey()) : e.getKey());
            if (out.size() >= max) break;
        }
        return out;
    }
}
