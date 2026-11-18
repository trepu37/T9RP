package dev.rut9.engine;

import java.util.Locale;
import java.util.Set;

/** Исправление неверной раскладки: «ghbdtn» -> «привет». */
public final class LayoutFixer {
    private LayoutFixer() {}

    private static final String EN = "qwertyuiopasdfghjklzxcvbnm";
    private static final String RU = "йцукенгшщзфывапролдячсмить";
    private static final Set<String> KEEP = Set.of("pvp", "pve", "afk", "ggs", "wtf", "omg", "lol", "okay", "the",
            "and", "you", "for", "wow", "tnt", "nether");

    public static String fix(String word, Lexicon lex) {
        if (word.length() < 3) return null;
        String lower = word.toLowerCase(Locale.ROOT);
        if (KEEP.contains(lower)) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            int k = EN.indexOf(lower.charAt(i));
            if (k < 0) return null;
            sb.append(RU.charAt(k));
        }
        String r = sb.toString();
        return lex.freq(r) >= 1500 ? r : null;
    }
}
