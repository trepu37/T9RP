package dev.rut9.ui;

import java.util.List;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/** Минималистичная панель на 3 варианта прямо над полем ввода чата (обычный чат, не РП). Имена — Yarn 1.21.8. */
public final class SuggestionPanel {
    public static final int H = 16;
    public static final int SLOTS = 3;

    private static int width(int sw) { return Math.min(sw - 8, 390); }
    private static int top(int sh) { return sh - 14 - H - 2; }

    public void render(DrawContext ctx, TextRenderer tr, List<String> s, int mx, int my,
                       boolean dark, String status, int sw, int sh) {
        int x = 4, y = top(sh), w = width(sw), cw = w / SLOTS;
        int bg = dark ? 0xCC101010 : 0xE6F2F2F2;
        int fg = dark ? 0xFFFFFFFF : 0xFF202020;
        int dim = dark ? 0xFF8A8A8A : 0xFF6A6A6A;
        int hover = dark ? 0x40FFFFFF : 0x30000000;
        int border = dark ? 0xFF3A3A3A : 0xFFBDBDBD;

        ctx.fill(x, y, x + w, y + H, bg);
        ctx.drawBorder(x, y, w, H, border);
        if (status != null) {
            ctx.drawText(tr, status, x + 6, y + 4, dim, false);
            return;
        }
        for (int i = 0; i < SLOTS; i++) {
            int cx = x + i * cw;
            if (i > 0) ctx.fill(cx, y + 3, cx + 1, y + H - 3, border);
            if (i >= s.size()) continue;
            String t = tr.trimToWidth(s.get(i), cw - 10);
            if (mx >= cx && mx < cx + cw && my >= y && my < y + H) {
                ctx.fill(cx + 1, y + 1, cx + cw, y + H - 1, hover);
            }
            ctx.drawText(tr, t, cx + (cw - tr.getWidth(t)) / 2, y + 4, fg, dark);
        }
    }

    /** @return индекс варианта под курсором или -1 */
    public int hit(double mx, double my, int sw, int sh, int count) {
        int x = 4, y = top(sh), w = width(sw), cw = w / SLOTS;
        if (mx < x || mx >= x + w || my < y || my >= y + H) return -1;
        int i = (int) ((mx - x) / cw);
        return i >= 0 && i < count && i < SLOTS ? i : -1;
    }
}
