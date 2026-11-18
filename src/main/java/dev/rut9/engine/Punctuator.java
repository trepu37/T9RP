package dev.rut9.engine;

import java.util.*;

/** Расстановка запятых и «?» при отправке: «привет как дела» -> «Привет, как дела?». */
public final class Punctuator {
    private Punctuator() {}

    private static final Set<String> GREET = Set.of("привет", "здравствуйте", "здравствуй", "здорово", "приветствую",
            "хай", "приветик", "слушай", "кстати", "короче");
    private static final Set<String> GREET2 = Set.of("добрый день", "добрый вечер", "доброе утро", "доброй ночи");
    private static final Set<String> CONJ = Set.of("но", "зато", "однако", "хотя", "чтобы", "который", "которая",
            "которое", "которые", "которого", "которой", "потому");
    private static final Set<String> QUESTION = Set.of("как", "что", "где", "куда", "откуда", "когда", "почему", "зачем",
            "кто", "какой", "какая", "какое", "какие", "сколько", "чей", "чья", "чье", "отчего", "можно", "можешь",
            "можете", "поможешь", "поможете", "правда");

    private static String bare(String t) {
        int a = 0, b = t.length();
        while (a < b && !Character.isLetterOrDigit(t.charAt(a))) a++;
        while (b > a && !Character.isLetterOrDigit(t.charAt(b - 1))) b--;
        return t.substring(a, b).toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    private static void comma(List<String> tk, int i) {
        String s = tk.get(i);
        if (!s.isEmpty() && Character.isLetterOrDigit(s.charAt(s.length() - 1))) tk.set(i, s + ",");
    }

    public static String apply(String text, boolean addPeriod) {
        String t = text.strip();
        if (t.isEmpty() || t.startsWith("/")) return text;
        List<String> tk = new ArrayList<>(Arrays.asList(t.split("\\s+")));

        for (int i = 1; i < tk.size(); i++) {
            if (CONJ.contains(bare(tk.get(i)))) comma(tk, i - 1);
        }

        int clause = 0;
        if (tk.size() > 1) {
            if (GREET.contains(bare(tk.get(0)))) {
                comma(tk, 0);
                clause = 1;
            } else if (tk.size() > 2 && GREET2.contains(bare(tk.get(0)) + " " + bare(tk.get(1)))) {
                comma(tk, 1);
                clause = 2;
            }
        }

        String last = tk.get(tk.size() - 1);
        if (Character.isLetterOrDigit(last.charAt(last.length() - 1))) {
            boolean q = clause < tk.size() && QUESTION.contains(bare(tk.get(clause)));
            if (bare(last).equals("ли")) q = true;
            if (q) tk.set(tk.size() - 1, last + "?");
            else if (addPeriod && tk.size() - clause >= 3) tk.set(tk.size() - 1, last + ".");
        }

        String first = tk.get(0);
        if (!first.isEmpty() && Tx.isCyr(first.charAt(0))) tk.set(0, Tx.capitalize(first));
        return String.join(" ", tk);
    }
}
