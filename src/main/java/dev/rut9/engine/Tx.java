package dev.rut9.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Утилиты работы с текстом. */
public final class Tx {
    private Tx() {}

    public static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '\'';
    }

    public static boolean isCyr(char c) {
        return (c >= 'а' && c <= 'я') || (c >= 'А' && c <= 'Я') || c == 'ё' || c == 'Ё';
    }

    public static boolean isCyrillicWord(String w) {
        if (w.isEmpty()) return false;
        boolean letter = false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (isCyr(c)) letter = true;
            else if (c != '-') return false;
        }
        return letter;
    }

    public static boolean isSentenceEnd(char c) {
        return c == '.' || c == '!' || c == '?' || c == '…';
    }

    /** Незавершённое слово в конце строки (может быть пустым). */
    public static String trailingWord(String s) {
        int i = s.length();
        while (i > 0 && isWordChar(s.charAt(i - 1))) i--;
        while (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '\'')) i++;
        return s.substring(i);
    }

    private static String strip(String w) {
        int a = 0, b = w.length();
        while (a < b && (w.charAt(a) == '-' || w.charAt(a) == '\'')) a++;
        while (b > a && (w.charAt(b - 1) == '-' || w.charAt(b - 1) == '\'')) b--;
        return w.substring(a, b);
    }

    /** Слова в нижнем регистре, ё нормализуется в е. */
    public static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= s.length(); i++) {
            if (i < s.length() && isWordChar(s.charAt(i))) {
                sb.append(s.charAt(i));
            } else if (sb.length() > 0) {
                String w = strip(sb.toString());
                if (!w.isEmpty()) out.add(w.toLowerCase(Locale.ROOT).replace('ё', 'е'));
                sb.setLength(0);
            }
        }
        return out;
    }

    public static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    public static String matchCase(String original, String replacement) {
        if (original.isEmpty()) return replacement;
        boolean allUp = original.length() > 1, anyLetter = false;
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            if (Character.isLetter(c)) {
                anyLetter = true;
                if (!Character.isUpperCase(c)) allUp = false;
            }
        }
        if (anyLetter && allUp) return replacement.toUpperCase(Locale.ROOT);
        if (Character.isUpperCase(original.charAt(0))) return capitalize(replacement);
        return replacement;
    }

    /** true, если head пуст или заканчивается концом предложения. */
    public static boolean sentenceStart(String head) {
        int i = head.length() - 1;
        while (i >= 0 && Character.isWhitespace(head.charAt(i))) i--;
        return i < 0 || isSentenceEnd(head.charAt(i));
    }

    /** Текущее (последнее) предложение из head. */
    public static String sentenceOf(String head) {
        int cut = 0;
        for (int i = 0; i < head.length(); i++) if (isSentenceEnd(head.charAt(i))) cut = i + 1;
        return head.substring(cut);
    }
}
