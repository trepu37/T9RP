package dev.rut9.ui;

import dev.rut9.rp.RpEngine;
import java.util.List;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;

/**
 * Серый «призрак» продолжения прямо в строке ввода + компактный список из трёх вариантов над строкой.
 * Выбранный вариант подсвечен; Tab принимает его, Вверх/Вниз переключают.
 */
public final class RpGhost {
    private static final int ROW = 11;

    private static int width(int sw) { return Math.min(sw - 8, 390); }
    private static int top(int sh, int n) { return sh - 14 - n * ROW - 4; }

    public void render(DrawContext ctx, TextRenderer tr, TextFieldWidget f, RpEngine rp, int mx, int my,
                       boolean dark, int sw, int sh) {
        List<String> v = rp.variants();
        if (v.isEmpty()) return;
        int sel = rp.selected();

        // 1) призрак в самой строке ввода
        String text = f.getText();
        int tw = tr.getWidth(text);
        int room = f.getWidth() - tw - 6;
        if (room > 10) {
            String g = tr.trimToWidth(v.get(sel), room);
            ctx.drawText(tr, g, f.getX() + tw, f.getY(), dark ? 0xFF7F7F7F : 0xFF5A5A5A, false);
        }

        // 2) список вариантов
        int n = v.size(), x = 4, w = width(sw), y = top(sh, n);
        ctx.fill(x, y, x + w, y + n * ROW + 3, dark ? 0xCC101010 : 0xE6F2F2F2);
        ctx.drawBorder(x, y, w, n * ROW + 3, dark ? 0xFF3A3A3A : 0xFFBDBDBD);
        for (int i = 0; i < n; i++) {
            int ry = y + 2 + i * ROW;
            boolean hover = mx >= x && mx < x + w && my >= ry && my < ry + ROW;
            if (i == sel || hover) ctx.fill(x + 1, ry - 1, x + w - 1, ry + ROW - 1, dark ? 0x40FFFFFF : 0x30000000);
            String label = (i == sel ? "> " : "  ") + (i + 1) + ". " + v.get(i).strip();
            ctx.drawText(tr, tr.trimToWidth(label, w - 58), x + 4, ry,
                    i == sel ? (dark ? 0xFFFFFFFF : 0xFF202020) : (dark ? 0xFF8A8A8A : 0xFF6A6A6A), false);
        }
        String hint = rp.llmPending() ? "LLM... Tab" : "Tab / Up-Down";
        ctx.drawText(tr, hint, x + w - tr.getWidth(hint) - 4, y + 2, dark ? 0xFF6A6A6A : 0xFF8A8A8A, false);
    }

    /** @return индекс строки списка под курсором или -1 */
    public int hit(double mx, double my, int sw, int sh, int count) {
        int x = 4, w = width(sw), y = top(sh, count);
        if (count <= 0 || mx < x || mx >= x + w || my < y || my >= y + count * ROW + 3) return -1;
        int i = (int) ((my - y - 2) / ROW);
        return i >= 0 && i < count ? i : -1;
    }
}
