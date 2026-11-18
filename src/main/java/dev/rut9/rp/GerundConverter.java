package dev.rut9.rp;

import java.util.Set;

/** «протянул» -> «протянув», «повернулся» -> «повернувшись», «вошёл» -> «войдя». */
final class GerundConverter {
    private GerundConverter() {}

    /** Существительные на «-л», которые нельзя принимать за глагол. */
    private static final Set<String> NOT_VERBS = Set.of("стол", "ствол", "угол", "пол", "вол", "гол", "кол", "осел",
            "орел", "котел", "козел", "бокал", "вокзал", "зал", "металл", "канал", "сигнал", "журнал", "штурвал",
            "арсенал", "капитал", "портал", "терминал", "сериал", "финал", "идеал", "рубль", "бал", "вал", "мел", "хол");

    /**
     * @param w     слово в нормализованном виде (нижний регистр, е вместо ё)
     * @param first true для первого слова действия: тогда допускаем и глаголы, которых нет в verbs.txt
     * @return деепричастие или null, если слово не похоже на глагол прошедшего времени
     */
    static String convert(String w, RpData d, boolean first) {
        String ex = d.explicitGerund(w);
        if (ex != null) return ex;
        boolean known = d.isPastVerb(w);

        if (known) { // неправильные основы: -шёл, -нёс, -вёз, -вёл
            String irr = irregular(w);
            if (irr != null) return irr;
        }
        if (!known && !(first && w.length() >= 4 && !NOT_VERBS.contains(w))) return null;

        for (String suf : new String[]{"лся", "лась", "лось", "лись"}) {
            if (w.endsWith(suf)) return w.substring(0, w.length() - suf.length()) + "вшись";
        }
        if (w.endsWith("ла") || w.endsWith("ло") || w.endsWith("ли")) return w.substring(0, w.length() - 2) + "в";
        if (w.endsWith("л")) return w.substring(0, w.length() - 1) + "в";
        return null;
    }

    private static String irregular(String w) {
        for (String suf : new String[]{"шел", "шла", "шло", "шли"}) {
            if (w.endsWith(suf)) {
                String pre = w.substring(0, w.length() - 3);
                return pre.endsWith("при") ? pre + "дя" : pre + "йдя"; // пришёл -> придя, вышел -> выйдя
            }
        }
        String[] consonantEnds = {"", "ла", "ло", "ли"}; // нес / несла
        String[] vowelEnds = {"", "а", "о", "и"};         // вел / вела
        Object[][] roots = {{"нес", "неся", consonantEnds}, {"вез", "везя", consonantEnds}, {"вел", "ведя", vowelEnds}};
        for (Object[] r : roots) {
            String root = (String) r[0];
            for (String end : (String[]) r[2]) {
                if (w.endsWith(root + end)) return w.substring(0, w.length() - root.length() - end.length()) + r[1];
            }
        }
        return null;
    }
}
