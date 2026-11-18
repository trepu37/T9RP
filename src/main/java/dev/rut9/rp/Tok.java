package dev.rut9.rp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Слово + окружающая пунктуация: lead «(, core «слово», trail «,.!?». Правила работают с core. */
final class Tok {
    String lead;
    String core;
    String trail;

    Tok(String lead, String core, String trail) {
        this.lead = lead;
        this.core = core;
        this.trail = trail;
    }

    static Tok word(String core) { return new Tok("", core, ""); }

    /** Нормализованный core: нижний регистр, ё -> е. */
    String n() { return norm(core); }

    static String norm(String s) { return s.toLowerCase(Locale.ROOT).replace('ё', 'е'); }

    @Override public String toString() { return lead + core + trail; }

    static List<Tok> split(String text) {
        List<Tok> out = new ArrayList<>();
        for (String p : text.strip().split("\\s+")) {
            if (p.isEmpty()) continue;
            int a = 0, b = p.length();
            while (a < b && !Character.isLetterOrDigit(p.charAt(a))) a++;
            while (b > a && !Character.isLetterOrDigit(p.charAt(b - 1))) b--;
            if (a == b) out.add(new Tok(p, "", ""));
            else out.add(new Tok(p.substring(0, a), p.substring(a, b), p.substring(b)));
        }
        return out;
    }

    static String join(List<Tok> tk) {
        StringBuilder sb = new StringBuilder();
        for (Tok t : tk) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        return sb.toString();
    }
}
