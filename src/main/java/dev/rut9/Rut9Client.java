package dev.rut9;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.rut9.config.Rut9Config;
import dev.rut9.engine.T9Engine;
import dev.rut9.rp.RpEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.function.Consumer;
import java.util.function.Supplier;

public final class Rut9Client implements ClientModInitializer {
    private static Rut9Client instance;

    private Rut9Config config;
    private T9Engine engine;

    public static Rut9Client get() { return instance; }

    public Rut9Config config() { return config; }

    public T9Engine engine() { return engine; }

    @Override
    public void onInitializeClient() {
        instance = this;
        config = Rut9Config.load();
        engine = new T9Engine(config);
        engine.initAsync();

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> engine.shutdown());

        // ---- память чата для контекстных подсказок ----
        // Сообщения сервера (на РП-серверах чаще всего приходят как «игровые»), системные строки — без оверлея (action bar).
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) engine.rp().ctx().addIncoming(message.getString());
        });
        // Подписанные чат-сообщения игроков.
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) ->
                engine.rp().ctx().addIncoming(message.getString()));
        // Свои команды: «me достал телефон» -> нужны для связки /me -> /do.
        ClientSendMessageEvents.COMMAND.register(cmd -> engine.rp().ctx().addOwnCommand(cmd));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("t9")
                        .then(toggle("enabled", () -> config.enabled, v -> config.enabled = v))
                        .then(toggle("panel", () -> config.panelVisible, v -> config.panelVisible = v))
                        .then(toggle("autocorrect", () -> config.autocorrect, v -> config.autocorrect = v))
                        .then(toggle("predict", () -> config.predictions, v -> config.predictions = v))
                        .then(toggle("learn", () -> config.learning, v -> config.learning = v))
                        .then(toggle("punctuation", () -> config.smartPunctuation, v -> config.smartPunctuation = v))
                        .then(toggle("period", () -> config.addPeriod, v -> config.addPeriod = v))
                        .then(toggle("capitalize", () -> config.autoCapitalize, v -> config.autoCapitalize = v))
                        .then(toggle("layout", () -> config.layoutFix, v -> config.layoutFix = v))
                        .then(toggle("dark", () -> config.darkTheme, v -> config.darkTheme = v))
                        .then(toggle("tab", () -> config.tabAccepts, v -> config.tabAccepts = v))
                        // ---- РП ----
                        .then(toggle("rp", () -> config.rpEnabled, v -> config.rpEnabled = v))
                        .then(toggle("ghost", () -> config.rpGhost, v -> config.rpGhost = v))
                        .then(toggle("autoformat", () -> config.rpAutoFormat, v -> config.rpAutoFormat = v))
                        .then(toggle("spell", () -> config.rpSpellcheck, v -> config.rpSpellcheck = v))
                        .then(toggle("rplayout", () -> config.rpLayoutFix, v -> config.rpLayoutFix = v))
                        .then(toggle("llm", () -> config.llmEnabled, v -> config.llmEnabled = v))
                        .then(ClientCommandManager.literal("reload").executes(ctx -> {
                            Rut9Config fresh = Rut9Config.load(); // подхватить URL/ключ/модель из config.json
                            config.llmUrl = fresh.llmUrl;
                            config.llmApiKey = fresh.llmApiKey;
                            config.llmModel = fresh.llmModel;
                            config.llmDebounceMs = fresh.llmDebounceMs;
                            config.llmTimeoutMs = fresh.llmTimeoutMs;
                            config.llmChatLines = fresh.llmChatLines;
                            config.llmTemperature = fresh.llmTemperature;
                            config.rpChatLines = fresh.rpChatLines;
                            config.polishKey = fresh.polishKey;
                            engine.rp().runAsync(engine.rp()::load); // chains.json / rp_*.txt
                            ctx.getSource().sendFeedback(Text.literal("T9: настройки и РП-данные перезагружены"));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("llmtest").executes(ctx -> {
                            RpEngine rp = engine.rp();
                            FabricClientCommandSource src = ctx.getSource();
                            if (!rp.llmConfigured()) {
                                src.sendFeedback(Text.literal("T9: LLM выключена или не задан llmUrl (config/rut9/config.json, затем /t9 reload)"));
                                return 0;
                            }
                            src.sendFeedback(Text.literal("T9: проверяю " + rp.llmEndpoint() + " …"));
                            MinecraftClient mc = MinecraftClient.getInstance();
                            rp.runAsync(() -> {
                                String msg;
                                try {
                                    msg = "T9: LLM отвечает: " + rp.llmPing();
                                } catch (Exception e) {
                                    msg = "T9: LLM недоступна (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
                                }
                                final String m = msg;
                                mc.execute(() -> src.sendFeedback(Text.literal(m)));
                            });
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reset").executes(ctx -> {
                            engine.resetUser();
                            ctx.getSource().sendFeedback(Text.literal("T9: личный словарь очищен"));
                            return 1;
                        }))
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(Text.literal(
                                    "T9: /t9 <enabled|panel|autocorrect|predict|learn|punctuation|period|capitalize|layout|dark|tab|rp|ghost|autoformat|spell|rplayout|llm|reload|llmtest|reset>. "
                                            + "F8 — панель, F9 — причесать РП-строку, Tab — принять, Вверх/Вниз — вариант."));
                            return 1;
                        })));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> toggle(String name, Supplier<Boolean> get, Consumer<Boolean> set) {
        return ClientCommandManager.literal(name).executes(ctx -> {
            boolean nv = !get.get();
            set.accept(nv);
            config.save();
            ctx.getSource().sendFeedback(Text.literal("T9 " + name + ": " + (nv ? "вкл" : "выкл")));
            return 1;
        });
    }
}
