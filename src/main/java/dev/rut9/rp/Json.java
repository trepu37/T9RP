package dev.rut9.rp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Крошечный JSON-парсер без зависимостей (допускает комментарии двух видов — удобно для chains.json). */
final class Json {
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        Json j = new Json(text);
        Object o = j.value();
        j.ws();
        if (j.i != j.s.length()) throw j.err("лишние данные после значения");
        return o;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : Map.of(); }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : List.of(); }

    static String text(Object o) { return o instanceof String str ? str : null; }

    static double num(Object o, double def) { return o instanceof Number n ? n.doubleValue() : def; }

    /** Строка JSON с кавычками и экранированием. */
    static String quote(String v) {
        StringBuilder sb = new StringBuilder("\"");
        for (int k = 0; k < v.length(); k++) {
            char c = v.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private IllegalArgumentException err(String m) {
        return new IllegalArgumentException("JSON: " + m + " (позиция " + i + ")");
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '\uFEFF') i++;
            else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int e = s.indexOf("*/", i + 2);
                i = e < 0 ? s.length() : e + 2;
            } else break;
        }
    }

    private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

    private void expect(char c) {
        ws();
        if (!peek(c)) throw err("ожидался символ '" + c + "'");
        i++;
    }

    private Object value() {
        ws();
        if (i >= s.length()) throw err("неожиданный конец");
        char c = s.charAt(i);
        if (c == '{') return obj();
        if (c == '[') return arr();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> obj() {
        i++;
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (peek('}')) { i++; return m; }
        while (true) {
            ws();
            String k = string();
            expect(':');
            m.put(k, value());
            ws();
            if (peek(',')) { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> arr() {
        i++;
        List<Object> l = new ArrayList<>();
        ws();
        if (peek(']')) { i++; return l; }
        while (true) {
            l.add(value());
            ws();
            if (peek(',')) { i++; continue; }
            expect(']');
            return l;
        }
    }

    private String string() {
        ws();
        if (!peek('"')) throw err("ожидалась строка");
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 > s.length()) throw err("обрыв \\u-последовательности");
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> sb.append(e); // \" \\ \/
            }
        }
        throw err("незакрытая строка");
    }

    private Object number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw err("неожиданный символ '" + s.charAt(i) + "'");
        return Double.parseDouble(s.substring(st, i));
    }
}
