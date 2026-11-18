package dev.rut9.rp;

import java.util.Locale;

/** Четыре поддерживаемые РП-команды. /ame на сервере нет, поэтому не распознаётся. */
public enum RpCommand {
    ME("me"), DO("do"), TODO("todo"), TRY("try");

    public final String word;

    RpCommand(String word) { this.word = word; }

    /** Разобранная строка чата: команда + тело (всё после «/cmd »). */
    public record Parsed(RpCommand cmd, String body) {
        public String prefix() { return "/" + cmd.word + " "; }
    }

    /**
     * «/me текст» -> Parsed(ME, "текст"). Возвращает null, если это не РП-команда
     * или после команды ещё нет пробела («/me» без пробела — игрок пока печатает саму команду).
     */
    public static Parsed parse(String text) {
        if (text == null || text.length() < 4 || text.charAt(0) != '/') return null;
        int sp = text.indexOf(' ');
        if (sp < 0) return null;
        String w = text.substring(1, sp).toLowerCase(Locale.ROOT);
        for (RpCommand c : values()) {
            if (c.word.equals(w)) return new Parsed(c, text.substring(sp + 1));
        }
        return null;
    }
}
