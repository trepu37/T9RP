package dev.rut9.ui;

import dev.rut9.Rut9Client;
import dev.rut9.config.Rut9Config;
import dev.rut9.engine.T9Engine;
import dev.rut9.engine.Tx;
import dev.rut9.rp.RpCommand;
import dev.rut9.rp.RpEngine;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;

/**
 * Логика T9 в поле ввода чата.
 * Обычный текст: панель из 3 слов, автозамена, пунктуация (как в 1.0.0).
 * РП-команды /me /do /todo /try: серый призрак + 3 варианта (Tab, Вверх/Вниз), автоправка на лету,
 * полное форматирование по правилам сервера при Enter, «причёсывание» по F9.
 */
public final class ChatT9 {
    private static final ChatT9 INSTANCE = new ChatT9();
    private static final int MAX_LEN = 256;

    public static ChatT9 get() { return INSTANCE; }

    private record Undo(String original, String restored, String applied) {}

    private final SuggestionPanel panel = new SuggestionPanel();
    private final RpGhost ghost = new RpGhost();
    private String lastText = "";
    private Undo undo;

    private static boolean delimiter(char c) {
        return c == ' ' || c == ',' || c == '.' || c == '!' || c == '?' || c == ';' || c == ':' || c == ')';
    }

    private static RpCommand.Parsed rp(Rut9Config cfg, String text) {
        return cfg.rpEnabled ? RpCommand.parse(text) : null;
    }

    public void onOpen(TextFieldWidget f) {
        lastText = f.getText();
        undo = null;
        refresh(lastText);
    }

    /** Пересчитать подсказки под текущий текст (РП или обычный чат). */
    private void refresh(String text) {
        Rut9Client c = Rut9Client.get();
        if (c == null) return;
        Rut9Config cfg = c.config();
        T9Engine eng = c.engine();
        RpCommand.Parsed p = rp(cfg, text);
        if (p != null) {
            eng.clearSuggestions();
            if (cfg.rpGhost) eng.rp().update(p.cmd(), p.body()); else eng.rp().clear();
        } else {
            eng.rp().clear();
            if (text.startsWith("/")) eng.clearSuggestions(); else eng.requestSuggestions(text);
        }
    }

    // ---------------------------------------------------------------- render / слежение за текстом

    public void onRender(TextFieldWidget f, DrawContext ctx, int mx, int my) {
        Rut9Client c = Rut9Client.get();
        if (c == null || !c.config().enabled) return;
        Rut9Config cfg = c.config();
        T9Engine eng = c.engine();

        String text = f.getText();
        if (!text.equals(lastText)) {
            String old = lastText;
            lastText = text;
            undo = null;
            RpCommand.Parsed p = rp(cfg, text);
            if (p != null) {
                if (!handleAutocorrectRp(f, old, text, cfg, eng)) refresh(text);
            } else if (text.startsWith("/")) {
                eng.clearSuggestions();
                eng.rp().clear();
            } else {
                eng.rp().clear();
                if (!handleAutocorrect(f, old, text, cfg, eng)) eng.requestSuggestions(text);
            }
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        String cur = f.getText();
        if (rp(cfg, cur) != null) {
            if (cfg.rpGhost) ghost.render(ctx, mc.textRenderer, f, eng.rp(), mx, my, cfg.darkTheme, sw, sh);
        } else if (cfg.panelVisible && !cur.startsWith("/")) {
            String status = eng.ready() ? null : "T9: загрузка словаря…";
            panel.render(ctx, mc.textRenderer, eng.suggestions(), mx, my, cfg.darkTheme, status, sw, sh);
        }
    }

    // ---------------------------------------------------------------- автозамена: обычный чат

    /** Автозамена слова при вводе пробела/знака препинания. @return true, если текст изменён. */
    private boolean handleAutocorrect(TextFieldWidget f, String old, String now, Rut9Config cfg, T9Engine eng) {
        if (!(cfg.autocorrect || cfg.autoCapitalize)) return false;
        if (now.length() != old.length() + 1 || !now.startsWith(old) || f.getCursor() != now.length()) return false;
        char d = now.charAt(now.length() - 1);
        if (!delimiter(d)) return false;

        String before = now.substring(0, now.length() - 1);
        String word = Tx.trailingWord(before);
        if (word.isEmpty()) return false;
        String head = before.substring(0, before.length() - word.length());

        String fixed = fixWord(word, head, cfg, eng);
        if (fixed == null || fixed.equals(word)) return false;

        String newText = head + fixed + d;
        apply(f, newText);
        undo = new Undo(word, head + word, newText);
        eng.requestSuggestions(newText);
        return true;
    }

    private String fixWord(String word, String head, Rut9Config cfg, T9Engine eng) {
        if (head.endsWith("@") || head.endsWith("#")) return null;
        String base = word;
        if (cfg.layoutFix && !Tx.isCyrillicWord(word)) {
            String lf = eng.fixLayout(word);
            if (lf != null) base = Tx.matchCase(word, lf);
        }
        if (!Tx.isCyrillicWord(base)) return null;

        String res = base;
        boolean start = Tx.sentenceStart(head);
        if (cfg.autocorrect) {
            List<String> ws = Tx.tokenize(Tx.sentenceOf(head));
            String prev = ws.isEmpty() ? "<s>" : ws.get(ws.size() - 1);
            String corr = eng.correct(base, prev, start);
            if (corr != null) res = corr;
        }
        if (cfg.autoCapitalize && start) res = Tx.capitalize(res);
        return res;
    }

    // ---------------------------------------------------------------- автозамена: РП

    /** Правка последнего слова РП-строки (раскладка + орфография) при пробеле/знаке/«*». Регистр не трогаем. */
    private boolean handleAutocorrectRp(TextFieldWidget f, String old, String now, Rut9Config cfg, T9Engine eng) {
        if (!(cfg.rpSpellcheck || cfg.rpLayoutFix)) return false;
        if (now.length() != old.length() + 1 || !now.startsWith(old) || f.getCursor() != now.length()) return false;
        char d = now.charAt(now.length() - 1);
        if (!delimiter(d) && d != '*') return false;

        String before = now.substring(0, now.length() - 1);
        RpCommand.Parsed p = RpCommand.parse(before);
        if (p == null) return false; // пробел сразу после «/me» — это ещё не слово
        String body = p.body();
        String word = Tx.trailingWord(body);
        if (word.isEmpty()) return false;
        String headBody = body.substring(0, body.length() - word.length());
        int star = headBody.lastIndexOf('*');
        if (star >= 0) headBody = headBody.substring(star + 1);

        String fixed = eng.rp().fixWordLive(word, headBody);
        if (fixed == null) return false;

        String head = before.substring(0, before.length() - word.length());
        String newText = head + fixed + d;
        apply(f, newText);
        undo = new Undo(word, head + word, newText);
        refresh(newText);
        return true;
    }

    private void apply(TextFieldWidget f, String text) {
        f.setText(text);
        lastText = f.getText();
    }

    // ---------------------------------------------------------------- клавиатура

    public boolean onKey(TextFieldWidget f, int key, int mods) {
        Rut9Client c = Rut9Client.get();
        if (c == null) return false;
        Rut9Config cfg = c.config();
        T9Engine eng = c.engine();
        if (!cfg.enabled) return false;

        if (key == GLFW.GLFW_KEY_F8) {
            cfg.panelVisible = !cfg.panelVisible;
            cfg.save();
            return true;
        }

        String text = f.getText();

        // Backspace сразу после автозамены — вернуть исходное слово и запомнить его как «своё».
        if (key == GLFW.GLFW_KEY_BACKSPACE && undo != null && text.equals(undo.applied())) {
            eng.ignore(undo.original());
            apply(f, undo.restored());
            undo = null;
            refresh(f.getText());
            return true;
        }

        RpCommand.Parsed p = rp(cfg, text);
        if (p != null) return onKeyRp(f, key, mods, cfg, eng);
        if (text.startsWith("/")) return false;

        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            finish(f, cfg, eng);
            return false; // отправку выполняет ванильный ChatScreen
        }
        if (key == GLFW.GLFW_KEY_TAB && cfg.tabAccepts && cfg.panelVisible && !eng.suggestions().isEmpty()) {
            pick(f, 0, eng);
            return true;
        }
        if ((mods & GLFW.GLFW_MOD_ALT) != 0 && key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_3) {
            int i = key - GLFW.GLFW_KEY_1;
            if (i < eng.suggestions().size()) {
                pick(f, i, eng);
                return true;
            }
        }
        return false;
    }

    private boolean onKeyRp(TextFieldWidget f, int key, int mods, Rut9Config cfg, T9Engine eng) {
        RpEngine rp = eng.rp();

        if (key == cfg.polishKey) { // F9: причесать строку
            polish(f, rp);
            return true;
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (cfg.rpAutoFormat) finishRp(f, cfg, eng);
            return false; // отправку выполняет ванильный ChatScreen
        }
        if (!cfg.rpGhost) return false;
        List<String> v = rp.variants();
        if (v.isEmpty()) return false;

        if (key == GLFW.GLFW_KEY_TAB) {
            acceptRp(f, rp.selected(), rp);
            return true;
        }
        if ((key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) && v.size() > 1) {
            rp.cycle(key == GLFW.GLFW_KEY_DOWN ? 1 : -1);
            return true;
        }
        if ((mods & GLFW.GLFW_MOD_ALT) != 0 && key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_3) {
            int i = key - GLFW.GLFW_KEY_1;
            if (i < v.size()) {
                acceptRp(f, i, rp);
                return true;
            }
        }
        return false;
    }

    private void acceptRp(TextFieldWidget f, int i, RpEngine rp) {
        String ins = rp.accept(i);
        if (ins == null) return;
        String text = f.getText();
        if (text.length() + ins.length() > MAX_LEN) ins = ins.substring(0, Math.max(0, MAX_LEN - text.length()));
        apply(f, text + ins);
        undo = null;
        refresh(f.getText());
    }

    /** F9: мгновенно — локальная правка, затем (если настроен LLM) литературная доводка. */
    private void polish(TextFieldWidget f, RpEngine rp) {
        String t = f.getText();
        String local = rp.formatLine(t);
        if (!local.equals(t)) {
            apply(f, local);
            undo = null;
            refresh(local);
        }
        if (rp.llmAvailable()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            rp.polishLlm(local, res -> mc.execute(() -> {
                if (f.getText().equals(local) && !res.equals(local)) {
                    apply(f, res);
                    refresh(res);
                }
            }));
        }
    }

    /** Перед отправкой РП-строки: привести к правилам, обучиться. */
    private void finishRp(TextFieldWidget f, Rut9Config cfg, T9Engine eng) {
        String t = f.getText();
        if (t.isBlank()) return;
        String out = eng.rp().formatLine(t);
        if (!out.equals(t)) apply(f, out);
        RpCommand.Parsed p = RpCommand.parse(out);
        if (p != null) eng.learn(p.body());
    }

    /** Перед отправкой обычного сообщения: исправить последнее слово, расставить знаки, обучиться. */
    private void finish(TextFieldWidget f, Rut9Config cfg, T9Engine eng) {
        String t = f.getText();
        if (t.isBlank()) return;
        String out = t;
        String w = Tx.trailingWord(out);
        if (!w.isEmpty()) {
            String head = out.substring(0, out.length() - w.length());
            String fixed = fixWord(w, head, cfg, eng);
            if (fixed != null) out = head + fixed;
        }
        if (cfg.smartPunctuation) out = eng.punctuate(out);
        if (!out.equals(t)) apply(f, out);
        eng.learn(out);
    }

    private void pick(TextFieldWidget f, int i, T9Engine eng) {
        List<String> s = eng.suggestions();
        if (i < 0 || i >= s.size()) return;
        String sug = s.get(i);
        String text = f.getText();
        String partial = Tx.trailingWord(text);
        String head = text.substring(0, text.length() - partial.length());
        String newText = head + sug + " ";
        apply(f, newText);
        eng.recordPick(head, sug);
        undo = null;
        eng.requestSuggestions(f.getText());
    }

    // ---------------------------------------------------------------- мышь

    public boolean onMouse(TextFieldWidget f, double mx, double my, int button) {
        Rut9Client c = Rut9Client.get();
        if (c == null || button != 0) return false;
        Rut9Config cfg = c.config();
        if (!cfg.enabled) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        String text = f.getText();
        if (rp(cfg, text) != null) {
            if (!cfg.rpGhost) return false;
            RpEngine rp = c.engine().rp();
            int i = ghost.hit(mx, my, sw, sh, rp.variants().size());
            if (i < 0) return false;
            acceptRp(f, i, rp);
            return true;
        }
        if (!cfg.panelVisible || text.startsWith("/")) return false;
        int i = panel.hit(mx, my, sw, sh, c.engine().suggestions().size());
        if (i < 0) return false;
        pick(f, i, c.engine());
        return true;
    }
}
