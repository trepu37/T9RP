package dev.rut9.rp;

import java.util.*;

/**
 * Граф РП-связок «контекст -> действие -> следствие» из chains.json (встроенный + config/rut9/chains.json).
 * Разделы: tags (темы чата), me (продолжения для /me, /try и действия в /todo), do (описания для /do),
 * speech (реплики для левой части /todo). Формат — в комментариях к самому chains.json.
 */
final class ContextGraph {
    record Variant(String text, Set<String> tags, double w) {}

    static final class Rule {
        String id = "";
        List<String> when = List.of();     // me: любое из этих начал слов должно встретиться в тексте
        List<String> and = List.of();      // me: и ещё любое из этих
        List<String> unless = List.of();   // me: ни одного из этих
        List<String> afterMe = List.of();  // do: основы из недавних своих РП-команд
        List<String> chat = List.of();     // do/speech: основы из последних строк чата
        double w = 1.0;
        List<Variant> next = List.of();
    }

    final Map<String, List<String>> tags = new LinkedHashMap<>();
    final List<Rule> me = new ArrayList<>();
    final List<Rule> doRules = new ArrayList<>();
    final List<Rule> speech = new ArrayList<>();

    void clear() {
        tags.clear();
        me.clear();
        doRules.clear();
        speech.clear();
    }

    /** Добавляет правила из JSON (можно вызывать несколько раз: встроенный файл, затем пользовательский). */
    void load(String json) {
        Map<String, Object> root = Json.map(Json.parse(json));
        for (Map.Entry<String, Object> e : Json.map(root.get("tags")).entrySet()) {
            List<String> stems = tags.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
            for (Object o : Json.list(e.getValue())) if (Json.text(o) != null) stems.add(Tok.norm(Json.text(o)));
        }
        readRules(root.get("me"), me);
        readRules(root.get("do"), doRules);
        readRules(root.get("speech"), speech);
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        for (Object x : Json.list(o)) if (Json.text(x) != null) out.add(Tok.norm(Json.text(x)));
        return out;
    }

    private static void readRules(Object o, List<Rule> target) {
        for (Object ro : Json.list(o)) {
            Map<String, Object> m = Json.map(ro);
            Rule r = new Rule();
            if (Json.text(m.get("id")) != null) r.id = Json.text(m.get("id"));
            r.when = strings(m.get("when"));
            r.and = strings(m.get("and"));
            r.unless = strings(m.get("unless"));
            r.afterMe = strings(m.get("afterMe"));
            r.chat = strings(m.get("chat"));
            r.w = Json.num(m.get("w"), 1.0);
            List<Variant> vs = new ArrayList<>();
            for (Object vo : Json.list(m.get("next"))) {
                if (vo instanceof String s) {
                    vs.add(new Variant(s.strip(), Set.of(), 1.0));
                } else {
                    Map<String, Object> vm = Json.map(vo);
                    String t = Json.text(vm.get("t"));
                    if (t == null || t.isBlank()) continue;
                    Set<String> tg = new HashSet<>();
                    for (Object x : Json.list(vm.get("tags"))) if (Json.text(x) != null) tg.add(Json.text(x));
                    vs.add(new Variant(t.strip(), tg, Json.num(vm.get("w"), 1.0)));
                }
            }
            r.next = vs;
            if (!vs.isEmpty()) target.add(r);
        }
    }
}
