package dev.rut9.rp;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;

/**
 * Языковые данные РП: глаголы прошедшего времени (+ деепричастия), существительные с падежами.
 * Файлы лежат в assets/rut9/rp/ и дополняются файлами с теми же именами в config/rut9/.
 */
public final class RpData {
    /** Падежи в порядке файла nouns.txt. */
    public static final int NOM = 0, GEN = 1, DAT = 2, ACC = 3, PREP = 4, INS = 5;

    public static final class Noun {
        public final String[] forms;
        public final Set<String> tags;

        Noun(String[] forms, Set<String> tags) {
            this.forms = forms;
            this.tags = tags;
        }

        public boolean has(String tag) { return tags.contains(tag); }
        public String nom() { return forms[NOM]; }
    }

    private record Hit(Noun noun, int caseIdx) {}

    private final Map<String, List<Hit>> byForm = new HashMap<>();
    private final Set<String> pastVerbs = new HashSet<>();
    private final Map<String, String> gerunds = new HashMap<>();
    private final Set<String> vocab = new HashSet<>();

    private static String norm(String s) { return Tok.norm(s); }

    public boolean isPastVerb(String normWord) { return pastVerbs.contains(normWord); }

    public String explicitGerund(String normWord) { return gerunds.get(normWord); }

    /** Все РП-слова (формы глаголов, существительных, слова из words.txt) — нормализованные. */
    public Set<String> vocab() { return vocab; }

    public Noun noun(String normForm) {
        List<Hit> h = byForm.get(normForm);
        return h == null ? null : h.get(0).noun();
    }

    public Set<Integer> casesOf(String normForm) {
        Set<Integer> s = new TreeSet<>();
        List<Hit> h = byForm.get(normForm);
        if (h != null) for (Hit x : h) s.add(x.caseIdx());
        return s;
    }

    // ------------------------------------------------------------------ загрузка

    /**
     * verbs.txt: слова через пробел — прошедшее время м.р. («достал»), необязательно «слово:деепричастие».
     * Формы ж./ср./мн. числа достраиваются автоматически.
     */
    public void loadVerbs(BufferedReader r) throws IOException {
        String line;
        while ((line = r.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            for (String tok : line.split("\\s+")) {
                String[] p = tok.split(":", 2);
                String w = norm(p[0]);
                if (w.length() < 3) continue;
                addVerbForms(w);
                if (p.length == 2 && !p[1].isBlank()) gerunds.put(w, p[1].strip());
            }
        }
    }

    private void addVerbForms(String w) {
        List<String> forms = new ArrayList<>();
        forms.add(w);
        if (w.endsWith("лся")) {
            String b = w.substring(0, w.length() - 3);
            forms.add(b + "лась"); forms.add(b + "лось"); forms.add(b + "лись");
        } else if (w.endsWith("шел")) {
            String b = w.substring(0, w.length() - 3);
            forms.add(b + "шла"); forms.add(b + "шло"); forms.add(b + "шли");
        } else if (w.endsWith("л")) {
            forms.add(w + "а"); forms.add(w + "о"); forms.add(w + "и");
        } else { // нес, вез, лег, смог ...
            forms.add(w + "ла"); forms.add(w + "ло"); forms.add(w + "ли");
        }
        pastVerbs.addAll(forms);
        vocab.addAll(forms);
    }

    /** nouns.txt: «им род дат вин пред твор [тег,тег]»; теги: container, holster, item, weapon, pl ... */
    public void loadNouns(BufferedReader r) throws IOException {
        String line;
        while ((line = r.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            if (p.length < 6) continue;
            String[] forms = Arrays.copyOf(p, 6);
            Set<String> tags = new HashSet<>();
            if (p.length > 6) tags.addAll(Arrays.asList(p[6].split(",")));
            Noun n = new Noun(forms, tags);
            for (int c = 0; c < 6; c++) {
                String f = norm(forms[c]);
                List<Hit> l = byForm.computeIfAbsent(f, k -> new ArrayList<>(1));
                l.add(new Hit(n, c));
                vocab.add(f);
            }
        }
    }

    /** words.txt: просто слова через пробел. */
    public void loadWords(BufferedReader r) throws IOException {
        String line;
        while ((line = r.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            for (String w : line.split("\\s+")) vocab.add(norm(w));
        }
    }
}
