package dev.rut9.engine;

import java.util.HashMap;
import java.util.Map;

/**
 * Лёгкий морфологический слой на правилах: управление предлогов (падежные окончания)
 * и согласование местоимений с глаголами. Это эвристика, а не полный морфоанализатор.
 */
final class Morphology {
    private Morphology() {}

    private static final Map<String, String[]> RULES = new HashMap<>();

    private static void reg(String[] keys, String... suffixes) {
        for (String k : keys) RULES.put(k, suffixes);
    }

    static {
        // предлоги
        reg(new String[]{"в", "на"}, "у", "ю", "е", "а", "я", "ь", "ие", "ию", "ы", "и");
        reg(new String[]{"к", "по"}, "у", "ю", "е", "ам", "ям", "ой");
        reg(new String[]{"без", "для", "до", "из", "от", "около", "после", "кроме", "вместо"}, "а", "я", "ы", "и", "у", "ю", "ей", "ов", "ев");
        reg(new String[]{"над", "под", "перед", "между"}, "ом", "ем", "ой", "ей", "ами", "ями", "ым", "им");
        reg(new String[]{"о", "об", "при"}, "е", "и", "ии", "ах", "ях");
        // местоимения -> глагол
        reg(new String[]{"я"}, "у", "ю", "л", "ла", "лся", "лась");
        reg(new String[]{"ты"}, "ешь", "ишь", "л", "ла", "ся");
        reg(new String[]{"он"}, "ет", "ит", "л", "ёт", "ется", "ится");
        reg(new String[]{"она", "оно"}, "ет", "ит", "ла", "ло", "ется", "ится");
        reg(new String[]{"мы"}, "ем", "им", "ли", "емся", "имся");
        reg(new String[]{"вы"}, "ете", "ите", "ли", "етесь", "итесь");
        reg(new String[]{"они"}, "ут", "ют", "ат", "ят", "ли", "утся", "ются");
    }

    static double bonus(String prev, String cand) {
        String[] s = RULES.get(prev);
        if (s == null || cand.length() < 3) return 0;
        for (String suf : s) if (cand.endsWith(suf)) return 1.1;
        return 0;
    }
}
