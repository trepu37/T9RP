package dev.rut9.rp;

import dev.rut9.config.Rut9Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Клиент OpenAI-совместимого /chat/completions (OpenAI, OpenRouter, LM Studio, Ollama с /v1 и т.п.).
 * Настройки читаются из конфига при каждом вызове, так что правка config.json + /t9 reload действует сразу.
 * Все методы блокирующие — вызывайте их только из фонового потока.
 */
final class LlmClient {
    private static final String SYSTEM_RULES = """
            Правила РП-команд Minecraft-сервера (команды /ame НЕТ):
            /me [текст] — действие персонажа от первого лица в прошедшем времени. Первая буква после команды строчная, в конце НЕТ точки.
            /do [текст] — описание окружения, состояния или результата от третьего лица. Первая буква заглавная, в конце точка (или «?» для вопроса).
            /todo [Фраза]*[действие] — речь и действие одновременно. Фраза до «*» с заглавной буквы. После «*» действие со строчной буквы, деепричастным оборотом (протянув, достав), без точки в конце.
            /try [текст] — спорное действие с шансом на неудачу; оформляется как /me (строчная буква, без точки).
            Пиши грамотно, литературно, кратко (4–14 слов), без мата и без повторов уже введённого текста.""";

    private final Rut9Config cfg;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(2000)).build();
    private volatile int failures;
    private volatile long pausedUntil;

    LlmClient(Rut9Config cfg) { this.cfg = cfg; }

    boolean configured() {
        return cfg.llmEnabled && cfg.llmUrl != null && !cfg.llmUrl.isBlank();
    }

    /** После трёх неудач подряд LLM отключается на минуту — мод тихо работает на локальном движке. */
    boolean available() {
        return configured() && System.currentTimeMillis() >= pausedUntil;
    }

    private void ok() { failures = 0; }

    private void fail() {
        if (++failures >= 3) {
            pausedUntil = System.currentTimeMillis() + 60_000;
            failures = 0;
        }
    }

    String endpoint() {
        String u = cfg.llmUrl.strip();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/chat/completions")) return u;
        if (u.endsWith("/v1")) return u + "/chat/completions";
        return u + "/v1/chat/completions";
    }

    // ------------------------------------------------------------------ публичные операции

    /** Три варианта продолжения. Пустой список — модель ответила непонятно. */
    List<String> continuations(RpCommand cmd, String body, boolean speechPart, List<String> chat, List<String> own) throws Exception {
        String where = switch (cmd) {
            case TODO -> speechPart ? "ЛЕВАЯ часть (реплика до «*»)" : "ПРАВАЯ часть (действие после «*»)";
            default -> "основной текст";
        };
        StringBuilder u = new StringBuilder();
        u.append("Последние строки игрового чата (старые сверху):\n");
        if (chat.isEmpty()) u.append("(пусто)\n");
        for (int i = chat.size() - 1; i >= 0; i--) u.append("- ").append(chat.get(i)).append('\n');
        if (!own.isEmpty()) {
            u.append("\nМои последние РП-действия (старые сверху):\n");
            for (int i = own.size() - 1; i >= 0; i--) u.append("- ").append(own.get(i)).append('\n');
        }
        u.append("\nКоманда: /").append(cmd.word).append(" (").append(where).append(")\n");
        u.append("Уже введено: «").append(body).append("»\n");
        u.append("Дай ровно 3 разных логичных варианта продолжения, которые идут сразу после введённого. ")
         .append("Если ввод оборван на середине слова — начни вариант с этого слова целиком. ")
         .append("Ответ — ТОЛЬКО JSON-массив из 3 строк, без пояснений.");
        String content = chat(SYSTEM_RULES, u.toString(), 160);
        return parseArray(content);
    }

    /** Литературная правка всей строки. Возвращает только тело команды. */
    String polish(RpCommand cmd, String body, List<String> chat) throws Exception {
        StringBuilder u = new StringBuilder();
        if (!chat.isEmpty()) {
            u.append("Контекст (последние строки чата, старые сверху):\n");
            for (int i = chat.size() - 1; i >= 0; i--) u.append("- ").append(chat.get(i)).append('\n');
            u.append('\n');
        }
        u.append("Команда: /").append(cmd.word).append('\n');
        u.append("Текст игрока: «").append(body).append("»\n");
        u.append("Исправь орфографию, окончания, падежи, пунктуацию; восстанови пропущенные слова; сделай плавным ")
         .append("литературным РП-текстом. Не добавляй новых событий, сохрани смысл. ");
        if (cmd == RpCommand.TODO) u.append("Сохрани символ «*» между фразой и действием. ");
        u.append("Ответ — ТОЛЬКО исправленный текст без команды и без кавычек.");
        String out = chat(SYSTEM_RULES, u.toString(), 200).strip();
        if (out.startsWith("«") && out.endsWith("»") && out.length() > 2) out = out.substring(1, out.length() - 1);
        if (out.startsWith("\"") && out.endsWith("\"") && out.length() > 2) out = out.substring(1, out.length() - 1);
        return out;
    }

    /** Проверка настроек для /t9 llmtest. Возвращает короткий ответ модели или бросает исключение. */
    String ping() throws Exception {
        return chat("Отвечай одним словом.", "Скажи «готово».", 10).strip();
    }

    // ------------------------------------------------------------------ HTTP

    private String chat(String system, String user, int maxTokens) throws Exception {
        String payload = "{\"model\":" + Json.quote(cfg.llmModel)
                + ",\"temperature\":" + cfg.llmTemperature
                + ",\"max_tokens\":" + maxTokens
                + ",\"stream\":false"
                + ",\"messages\":[{\"role\":\"system\",\"content\":" + Json.quote(system)
                + "},{\"role\":\"user\",\"content\":" + Json.quote(user) + "}]}";
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint()))
                .timeout(Duration.ofMillis(Math.max(500, cfg.llmTimeoutMs)))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (cfg.llmApiKey != null && !cfg.llmApiKey.isBlank()) rb.header("Authorization", "Bearer " + cfg.llmApiKey.strip());
        try {
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) throw new IllegalStateException("HTTP " + resp.statusCode());
            Map<String, Object> root = Json.map(Json.parse(resp.body()));
            List<Object> choices = Json.list(root.get("choices"));
            if (choices.isEmpty()) throw new IllegalStateException("пустой ответ модели");
            String content = Json.text(Json.map(Json.map(choices.get(0)).get("message")).get("content"));
            if (content == null) throw new IllegalStateException("нет текста в ответе");
            ok();
            return content;
        } catch (Exception e) {
            fail(); // ключ в сообщения об ошибках не попадает: в них только статус/тип ошибки
            throw e;
        }
    }

    /** Достаёт массив строк из ответа; если модель ответила списком/строками — разбирает их. */
    static List<String> parseArray(String content) {
        List<String> out = new ArrayList<>();
        int a = content.indexOf('['), b = content.lastIndexOf(']');
        if (a >= 0 && b > a) {
            try {
                for (Object o : Json.list(Json.parse(content.substring(a, b + 1)))) {
                    String t = Json.text(o);
                    if (t != null && !t.isBlank()) out.add(t.strip());
                }
            } catch (RuntimeException ignored) {
                out.clear();
            }
        }
        if (out.isEmpty()) {
            for (String line : content.split("\\R")) {
                String t = line.strip().replaceFirst("^(\\d+[.)]|[-•*])\\s*", "").strip();
                if (t.length() >= 2) out.add(t);
            }
        }
        return out.size() > 3 ? new ArrayList<>(out.subList(0, 3)) : out;
    }
}
