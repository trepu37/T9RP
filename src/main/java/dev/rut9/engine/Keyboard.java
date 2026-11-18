package dev.rut9.engine;

/** Модель опечаток: соседние клавиши ЙЦУКЕН и частые фонетические путаницы. */
final class Keyboard {
    private Keyboard() {}

    private static final String[] ROWS = {"йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю"};
    private static final String[] PAIRS = {"оа", "ие", "ъь", "зс", "дт", "бп", "вф", "гк", "жш", "ыи", "эе", "щш", "цс"};

    private static int[] pos(char c) {
        for (int r = 0; r < ROWS.length; r++) {
            int i = ROWS[r].indexOf(c);
            if (i >= 0) return new int[]{r, i};
        }
        return null;
    }

    static boolean adjacent(char a, char b) {
        if (a == b) return false;
        int[] p = pos(a), q = pos(b);
        if (p == null || q == null) return false;
        return Math.abs(p[0] - q[0]) <= 1 && Math.abs(p[1] - q[1]) <= 1;
    }

    static boolean confusable(char a, char b) {
        for (String s : PAIRS) {
            if ((s.charAt(0) == a && s.charAt(1) == b) || (s.charAt(0) == b && s.charAt(1) == a)) return true;
        }
        return false;
    }

    /** Бонус кандидату, если отличия от введённого слова похожи на типичную опечатку. */
    static double bonus(String typed, String cand) {
        if (typed.length() != cand.length()) return 0;
        double b = 0;
        for (int i = 0; i < typed.length(); i++) {
            char x = typed.charAt(i), y = cand.charAt(i);
            if (x == y) continue;
            if (confusable(x, y)) b += 1.2;
            else if (adjacent(x, y)) b += 1.0;
        }
        return b;
    }
}
