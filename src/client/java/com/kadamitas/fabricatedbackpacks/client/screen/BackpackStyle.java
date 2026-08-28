package com.kadamitas.fabricatedbackpacks.client.screen;

import net.minecraft.client.gui.GuiGraphics;

public final class BackpackStyle {
    public enum Surface { BODY, PANEL, RAIL, TITLE }
    public static final int TEXT = 0xFFF1DEBB, TITLE_TEXT = 0xFF57361D, MUTED_TEXT = 0xFFBBA58C;
    public static final int PANEL_TEXT = 0xFF514639;
    private BackpackStyle() {}
    /** x and y are the top-left of the native 16-pixel item area. */
    public static void slot(GuiGraphics g, int x, int y, boolean ghost) {
        g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF373737);
        g.fill(x, y, x + 16, y + 16, 0xFF6A6665);
        g.fill(x, y, x + 16, y + 1, 0xFF524F4E);
        g.fill(x, y + 1, x + 1, y + 16, 0xFF524F4E);
        g.fill(x, y + 16, x + 17, y + 17, 0xFFEECF8A);
        g.fill(x + 16, y, x + 17, y + 16, 0xFFEECF8A);
    }
    /** Empty physical upgrade cell; x and y use the same item-area origin as slot(). */
    public static void emptyUpgradeSlot(GuiGraphics g, int x, int y) {
        slot(g, x, y, false);
        for (int offset = 3; offset <= 11; offset += 4) {
            g.fill(x + offset, y + 2, x + offset + 2, y + 3, 0xFF373737);
            g.fill(x + offset, y + 13, x + offset + 2, y + 14, 0xFF373737);
            g.fill(x + 2, y + offset, x + 3, y + offset + 2, 0xFF373737);
            g.fill(x + 13, y + offset, x + 14, y + offset + 2, 0xFF373737);
        }
    }
    private static final int[][] COLORS = {
            {0xFFA6552F, 0xFFCA6839, 0xFF783715, 0xFF5B240A},
            {0xFFBDA285, 0xFFE7CAA3, 0xFF8B6E54, 0xFF3B2C1F},
            {0xFFA6552F, 0xFFCA6839, 0xFF783715, 0xFF5B240A},
            {0xFFECA535, 0xFFFBBC59, 0xFFFBBC59, 0xFFB86F02}
    };

    static void bevel(GuiGraphics g, int x, int y, int w, int h, int fill, int light, int dark, int edge) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, edge);
        if (w < 3 || h < 3) return;
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
        g.fill(x + 1, y + 1, x + w - 1, y + 2, light);
        g.fill(x + 1, y + 1, x + 2, y + h - 1, light);
        g.fill(x + 2, y + h - 2, x + w - 1, y + h - 1, dark);
        g.fill(x + w - 2, y + 2, x + w - 1, y + h - 1, dark);
    }

    public static void frame(GuiGraphics g, int x, int y, int width, int height, Surface surface) {
        int[] colors = COLORS[surface.ordinal()];
        bevel(g, x, y, width, height, colors[0], colors[1], colors[2], colors[3]);
        if ((surface == Surface.BODY || surface == Surface.RAIL) && width > 6 && height > 6)
            g.renderOutline(x + 2, y + 2, width - 4, height - 4, 0xFF220F06);
        if (surface == Surface.TITLE && width > 2 && height > 2)
            g.fill(x, y, x + width, y + 1, 0xFFCC7A00);
    }
}
