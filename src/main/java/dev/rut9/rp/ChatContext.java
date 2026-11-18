package dev.rut9.rp;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Память о чате: последние строки входящего чата и последние РП-команды самого игрока.
 * Заполняется событиями Fabric API (см. Rut9Client), читается движком подсказок.
 */
public final class ChatContext {
    public record Own(RpCommand cmd, String body, long time) {}

    private static final int MAX_LINES = 10;
    private static final int MAX_OWN = 6;
    /** Своя РП-команда «живёт» в памяти 3 минуты — дальше связка /me -> /do уже неактуальна. */
    private static final long OWN_TTL_MS = 180_000;

    private final Deque<String> lines = new ArrayDeque<>();
    private final Deque<Own> own = new ArrayDeque<>();

    public synchronized void addIncoming(String line) {
        if (line == null) return;
        line = line.strip();
        if (line.isEmpty()) return;
        lines.addFirst(line);
        while (lines.size() > MAX_LINES) lines.removeLast();
    }

    /** @param command команда без слэша, как её отдаёт ClientSendMessageEvents.COMMAND: «me достал телефон». */
    public synchronized void addOwnCommand(String command) {
        RpCommand.Parsed p = RpCommand.parse("/" + command);
        if (p == null || p.body().isBlank()) return;
        own.addFirst(new Own(p.cmd(), p.body().strip(), System.currentTimeMillis()));
        while (own.size() > MAX_OWN) own.removeLast();
    }

    /** Последние n строк чата, самая свежая — первая. Строки в нижнем регистре с ё -> е. */
    public synchronized List<String> recentLines(int n) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (out.size() >= n) break;
            out.add(Tok.norm(l));
        }
        return out;
    }

    /** То же, но с исходным регистром (для отправки в LLM). */
    public synchronized List<String> recentLinesRaw(int n) {
        List<String> out = new ArrayList<>();
        for (String l : lines) {
            if (out.size() >= n) break;
            out.add(l);
        }
        return out;
    }

    /** Свои недавние РП-команды, самая свежая — первая; устаревшие отбрасываются. */
    public synchronized List<Own> recentOwn(int n) {
        long now = System.currentTimeMillis();
        List<Own> out = new ArrayList<>();
        for (Own o : own) {
            if (out.size() >= n) break;
            if (now - o.time() <= OWN_TTL_MS) out.add(o);
        }
        return out;
    }

    public synchronized void clear() {
        lines.clear();
        own.clear();
    }
}
