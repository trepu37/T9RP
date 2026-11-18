package dev.rut9.rp;

import java.util.*;

/**
 * Правила «причёсывания» рваного ввода: недостающие предлоги («засунул карман» -> «засунул руку в карман»),
 * падежи после предлогов, запятые между действиями. Это эвристики на таблицах, а не полный морфоанализатор:
 * работают для слов из nouns.txt / verbs.txt (расширяются файлами в config/rut9/).
 */
final class Grammar {
    private Grammar() {}

    private static final Set<String> HAND_INTO = Set.of("засунул", "засунула", "засунули", "сунул", "сунула",
            "запустил", "запустила", "запихнул", "запихнула");
    private static final Set<String> HAND_WORDS = Set.of("руку", "руки", "ладонь", "правую", "левую");
    private static final Set<String> MOVE_INTO = Set.of("полез", "полезла", "залез", "залезла", "потянулся",
            "потянулась", "нырнул", "нырнула");
    /** Глаголы направленного действия: после «в/на» нужен винительный. */
    private static final Set<String> MOTION = Set.of("засунул", "засунула", "сунул", "сунула", "запустил", "полез",
            "полезла", "залез", "залезла", "потянулся", "потянулась", "положил", "положила", "убрал", "убрала",
            "спрятал", "спрятала", "бросил", "бросила", "кинул", "кинула", "поставил", "поставила", "вставил",
            "вставила", "швырнул", "нырнул", "запихнул", "запихнула", "сложил", "сложила");

    private static final Map<String, int[]> PREP = new HashMap<>();
    static {
        PREP.put("в", new int[]{RpData.ACC, RpData.PREP});
        PREP.put("на", new int[]{RpData.ACC, RpData.PREP});
        PREP.put("к", new int[]{RpData.DAT});
        PREP.put("по", new int[]{RpData.DAT});
        for (String p : new String[]{"из", "от", "у", "без", "для", "до", "около", "после"}) PREP.put(p, new int[]{RpData.GEN});
        PREP.put("с", new int[]{RpData.GEN, RpData.INS});
        PREP.put("со", new int[]{RpData.GEN, RpData.INS});
        PREP.put("над", new int[]{RpData.INS});
        PREP.put("перед", new int[]{RpData.INS});
        PREP.put("под", new int[]{RpData.INS, RpData.ACC});
        PREP.put("за", new int[]{RpData.ACC, RpData.INS});
        for (String p : new String[]{"о", "об", "при"}) PREP.put(p, new int[]{RpData.PREP});
    }

    /** Союзы/вводные слова, которые «склеивают» действия: перед ними запятую не ставим (или ставим раньше). */
    private static final Set<String> JOINERS = Set.of("и", "да", "или");
    private static final Set<String> CHAIN = Set.of("а", "но", "затем", "потом", "после", "чего", "чтобы", "пока",
            "когда", "тогда", "там", "который", "которая", "которое");
    private static final Set<String> ADVERBS = Set.of("быстро", "медленно", "аккуратно", "резко", "плавно",
            "осторожно", "неспешно", "спокойно", "сразу", "тут", "снова", "слегка", "неторопливо", "уверенно",
            "нервно", "молча", "внезапно", "мгновенно", "тихо", "громко", "небрежно", "ловко", "привычно",
            "неохотно", "коротко", "лениво", "вальяжно", "невозмутимо", "торопливо", "бегло", "внимательно");

    static boolean isPastVerb(String w, RpData d) {
        if (d.isPastVerb(w)) return true;
        return w.length() >= 6 && (w.endsWith("лся") || w.endsWith("лась") || w.endsWith("лись") || w.endsWith("лось"));
    }

    static void apply(List<Tok> tk, RpData d) {
        insertFrames(tk, d);
        fixCases(tk, d);
        commas(tk, d);
    }

    // ------------------------------------------------------------ недостающие предлоги

    private static void insertFrames(List<Tok> tk, RpData d) {
        for (int i = 0; i < tk.size(); i++) {
            String v = tk.get(i).n();
            if (HAND_INTO.contains(v) && i + 1 < tk.size()) {
                RpData.Noun nn = d.noun(tk.get(i + 1).n());
                if (nn != null && nn.has("container") && tk.get(i).trail.isEmpty()) {
                    tk.add(i + 1, Tok.word("руку"));
                    tk.add(i + 2, Tok.word("в"));
                    i += 2;
                } else if (HAND_WORDS.contains(tk.get(i + 1).n()) && i + 2 < tk.size()) {
                    RpData.Noun n2 = d.noun(tk.get(i + 2).n());
                    if (n2 != null && n2.has("container") && tk.get(i + 1).trail.isEmpty()) {
                        tk.add(i + 2, Tok.word("в"));
                        i += 2;
                    }
                }
            } else if (MOVE_INTO.contains(v) && i + 1 < tk.size() && tk.get(i).trail.isEmpty()) {
                RpData.Noun nn = d.noun(tk.get(i + 1).n());
                if (nn != null && nn.has("container")) { tk.add(i + 1, Tok.word("в")); i++; }
                else if (nn != null && nn.has("holster")) { tk.add(i + 1, Tok.word("к")); i++; }
            }
        }
    }

    // ------------------------------------------------------------ падежи после предлогов

    private static void fixCases(List<Tok> tk, RpData d) {
        for (int i = 1; i < tk.size(); i++) {
            Tok prep = tk.get(i - 1);
            int[] valid = PREP.get(prep.n());
            if (valid == null || !prep.trail.isEmpty() || !prep.lead.isEmpty()) continue;
            Tok t = tk.get(i);
            RpData.Noun noun = d.noun(t.n());
            if (noun == null) continue;
            Set<Integer> have = d.casesOf(t.n());
            boolean ok = false;
            for (int c : valid) if (have.contains(c)) { ok = true; break; }
            if (ok) continue;
            int want = valid[0];
            if (valid.length == 2 && valid[0] == RpData.ACC && valid[1] == RpData.PREP) {
                want = hasMotionBefore(tk, i - 1) ? RpData.ACC : RpData.PREP; // «в/на»: куда? / где?
            }
            String form = noun.forms[want];
            if (!t.core.isEmpty() && Character.isUpperCase(t.core.charAt(0))) form = Character.toUpperCase(form.charAt(0)) + form.substring(1);
            t.core = form;
        }
    }

    private static boolean hasMotionBefore(List<Tok> tk, int idx) {
        for (int j = idx - 1; j >= 0 && j >= idx - 4; j--) {
            if (MOTION.contains(tk.get(j).n())) return true;
            if (!tk.get(j).trail.isEmpty()) break;
        }
        return false;
    }

    // ------------------------------------------------------------ запятые между действиями

    private static void commas(List<Tok> tk, RpData d) {
        boolean seenVerb = false;
        for (int i = 0; i < tk.size(); i++) {
            if (!isPastVerb(tk.get(i).n(), d)) continue;
            if (seenVerb) {
                int j = i - 1;
                boolean join = false;
                while (j >= 0 && tk.get(j).trail.isEmpty() && tk.get(j).lead.isEmpty()
                        && (CHAIN.contains(tk.get(j).n()) || JOINERS.contains(tk.get(j).n()) || ADVERBS.contains(tk.get(j).n()))) {
                    if (JOINERS.contains(tk.get(j).n())) join = true;
                    j--;
                }
                if (!join && j >= 0 && tk.get(j).trail.isEmpty() && !tk.get(j).core.isEmpty()
                        && !PREP.containsKey(tk.get(j).n()) && !tk.get(j).n().equals("не")) {
                    tk.get(j).trail = ",";
                }
            }
            seenVerb = true;
        }
    }
}
