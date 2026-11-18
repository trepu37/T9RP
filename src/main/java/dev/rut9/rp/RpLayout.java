package dev.rut9.rp;

import dev.rut9.engine.Lexicon;
import dev.rut9.engine.Tx;

/**
 * Исправление «кривой раскладки» целой РП-строкой: «gjnzyekcz d rfhvfy» -> «потянулся в карман».
 * Команда («/me») остаётся латиницей — сюда попадает только тело. Учитывает контекст всей строки,
 * поэтому однобуквенные слова («d» -> «в», «r» -> «к») тоже исправляются, если соседние слова исправлены.
 */
final class RpLayout {
    private RpLayout() {}

    private static final String EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`";
    private static final String RU = "йцукенгшщзхъфывапролджэячсмитьбюё";

    static char map(char c) {
        int k = EN.indexOf(c);
        if (k >= 0) return RU.charAt(k);
        if (c >= 'A' && c <= 'Z') return Character.toUpperCase(RU.charAt(EN.indexOf(Character.toLowerCase(c))));
        return switch (c) {
            case '{' -> 'Х'; case '}' -> 'Ъ'; case ':' -> 'Ж'; case '"' -> 'Э'; case '<' -> 'Б'; case '>' -> 'Ю'; case '~' -> 'Ё';
            default -> c;
        };
    }

    private static boolean latLetter(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
    private static boolean alwaysLetter(char c) { return "[];'`{}:\"~".indexOf(c) >= 0; }

    /** Один «кусок» без пробелов -> кириллица (запятая/точка внутри слова — это «б»/«ю», на краю — пунктуация). */
    private static String convertPiece(String p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            boolean inner = i > 0 && i < p.length() - 1;
            if (latLetter(c) || alwaysLetter(c) || ((c == ',' || c == '.' || c == '<' || c == '>') && inner)) sb.append(map(c));
            else sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isLatinPiece(String p) {
        boolean any = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (Tx.isCyr(c)) return false;
            if (latLetter(c)) any = true;
        }
        return any;
    }

    private static String letters(String p) {
        StringBuilder sb = new StringBuilder();
        for (char c : p.toCharArray()) if (Character.isLetter(c) || c == '-') sb.append(c);
        return sb.toString();
    }

    private static boolean known(String converted, Lexicon lex) {
        String w = letters(converted);
        return w.length() >= 1 && lex.freq(w) >= 1500;
    }

    static String fix(String body, Lexicon lex) {
        String[] parts = body.split(" ", -1);
        int n = parts.length;
        boolean[] lat = new boolean[n], hit = new boolean[n];
        String[] conv = new String[n];
        int[] len = new int[n];
        int hits = 0, latCount = 0;
        for (int i = 0; i < n; i++) {
            lat[i] = isLatinPiece(parts[i]);
            if (!lat[i]) continue;
            conv[i] = convertPiece(parts[i]);
            len[i] = letters(parts[i]).length();
            if (len[i] >= 2) latCount++;
            if (len[i] >= 3 && known(conv[i], lex)) { hit[i] = true; hits++; }
        }
        if (hits == 0) return body;

        boolean[] take = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (!lat[i]) continue;
            if (len[i] >= 3 && (hit[i] || (latCount >= 2 && hits * 2 >= latCount))) take[i] = true;
        }
        for (int i = 0; i < n; i++) { // короткие слова — только рядом с исправленным соседом
            if (!lat[i] || take[i] || len[i] >= 3) continue;
            boolean near = (i > 0 && take[i - 1]) || (i + 1 < n && take[i + 1]);
            if (near && known(conv[i], lex)) take[i] = true;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(take[i] ? conv[i] : parts[i]);
        }
        return sb.toString();
    }
}
