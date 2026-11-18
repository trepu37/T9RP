package dev.rut9.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;

/**
 * Простой «семантический» слой: если в предложении есть слова-триггеры (по основам),
 * а курсор стоит после «потому что», предлагаются типичные причины/продолжения.
 * Формат файла: «основа,основа : вариант | вариант | вариант».
 */
public final class Topics {
    private record Topic(List<String> stems, List<String> cands) {}

    private final List<Topic> topics = new ArrayList<>();

    public void load(BufferedReader r) throws IOException {
        String line;
        while ((line = r.readLine()) != null) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split(":", 2);
            if (p.length < 2) continue;
            List<String> stems = new ArrayList<>();
            for (String s : p[0].split(",")) if (!s.isBlank()) stems.add(s.strip());
            List<String> cands = new ArrayList<>();
            for (String s : p[1].split("\\|")) if (!s.isBlank()) cands.add(s.strip());
            topics.add(new Topic(stems, cands));
        }
    }

    public List<String> suggest(List<String> words, String p1, String p2) {
        if (!"что".equals(p1) || !"потому".equals(p2)) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (Topic t : topics) {
            boolean hit = false;
            for (String w : words) {
                for (String s : t.stems()) if (w.startsWith(s)) { hit = true; break; }
                if (hit) break;
            }
            if (hit) out.addAll(t.cands());
        }
        return new ArrayList<>(out);
    }
}
