package dev.rut9.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Настройки мода. Файл: config/rut9/config.json. Часть полей меняется командой /t9,
 * остальные (URL/ключ LLM, модель, задержки) — правкой файла и командой /t9 reload.
 * Новые поля безопасно добавляются: у старых config.json значения по умолчанию остаются из инициализаторов.
 */
public final class Rut9Config {
    // ---------------- общий Т9 для обычного чата (как в 1.0.0) ----------------
    public boolean enabled = true;
    public boolean autocorrect = true;
    public boolean predictions = true;
    public boolean learning = true;
    public boolean panelVisible = true;
    public boolean darkTheme = true;
    public boolean layoutFix = true;        // «ghbdtn» -> «привет»
    public boolean autoCapitalize = true;   // заглавная в начале предложения
    public boolean smartPunctuation = true; // запятые/«?» при отправке
    public boolean addPeriod = false;       // точка в конце длинных фраз
    public boolean tabAccepts = true;       // Tab принимает первый вариант

    // ---------------- большой словарь (words_big.txt, скачивается при сборке) ----------------
    public boolean bigDictionary = true;
    /** Сколько самых частых слов загружать. 300000 ≈ 60–80 МБ ОЗУ; 1000000 мод не потянет. */
    public int dictMaxWords = 300000;
    /** Для скольких самых частых слов строить индекс исправления опечаток (остальные только «известны»). */
    public int dictIndexWords = 20000;

    // ---------------- РП-режим: /me /do /todo /try ----------------
    public boolean rpEnabled = true;        // умный РП-Т9 вообще
    public boolean rpGhost = true;          // серый текст-призрак + список из 3 вариантов
    public boolean rpAutoFormat = true;     // привести строку к правилам РП при Enter
    public boolean rpSpellcheck = true;     // орфография/падежи/запятые в РП-строках
    public boolean rpLayoutFix = true;      // «/me gjnzyekcz d rfhvfy» -> «/me потянулся в карман»
    public boolean rpLearn = true;          // запоминать выбранные варианты
    public int rpChatLines = 8;             // сколько последних строк чата анализировать (5–10)
    public int polishKey = 298;             // GLFW_KEY_F9: «причесать» текущую РП-строку

    // ---------------- LLM (любой OpenAI-совместимый API или Ollama) ----------------
    public boolean llmEnabled = false;
    /** База API: https://api.openai.com/v1  |  http://localhost:11434/v1 (Ollama)  |  полный путь до /chat/completions. */
    public String llmUrl = "";
    /** Ключ (для Ollama можно пустой). ВНИМАНИЕ: хранится открытым текстом в config.json. */
    public String llmApiKey = "";
    public String llmModel = "gpt-4o-mini";
    public int llmDebounceMs = 300;         // пауза в наборе перед запросом
    public int llmTimeoutMs = 3000;         // таймаут одного запроса
    public int llmChatLines = 5;            // сколько строк чата отправлять модели
    public double llmTemperature = 0.7;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("rut9");
    }

    public static Rut9Config load() {
        Path p = dir().resolve("config.json");
        try {
            if (Files.exists(p)) {
                try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                    Rut9Config c = GSON.fromJson(r, Rut9Config.class);
                    if (c != null) {
                        c.save(); // дописать в файл новые поля
                        return c;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        Rut9Config c = new Rut9Config();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("config.json"), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }
}
