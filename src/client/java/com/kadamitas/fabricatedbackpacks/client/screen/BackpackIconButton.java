package com.kadamitas.fabricatedbackpacks.client.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.Objects;

/** A native button with original pixel glyphs and an accessible, unpainted label. */
public final class BackpackIconButton extends Button {
    public enum Icon {
        DIRECTION("..#...#../.###..#../#####.#../..#...#../..#...#../..#.#####/..#..###./..#...#../........."),
        TRANSFER("......#../########./......#../........./..#....../.########/..#....../........./........."),
        TRANSFER_UP("....#..../...###.../..#####../.#######./...###.../...###.../...###.../...###.../........."),
        TRANSFER_DOWN("........./...###.../...###.../...###.../...###.../.#######./..#####../...###.../....#...."),
        SORT_ORDER("..#####../.##...##./.##...##./.#######./.##...##./.##...##./.##...##./.##...##./........."),
        PLUS("........./...###.../...###.../.#######./.#######./.#######./...###.../...###.../........."),
        MINUS("........./........./........./.#######./.#######./.#######./........./........./........."),
        POWER("....#..../..#.#.#../.##.#.##./##..#..##/##..#..##/##.....##/.##...##./..#####../...###..."),
        FILTER("#########/.#######./..#####../...###.../....#..../....#..../....#..../...##..../...##...."),
        TAG("#####..../#..###.../#..####../########./.########/..#######/...#####./....###../.....#..."),
        DAMAGE(".#######./.##..###./.###.###./.###..##./.##..###./.###.###./..##.##../...###.../....#...."),
        COMPONENTS("...###.../...#.#.../...###.../....#..../.#######./.#.....#./###...###/#.#...#.#/###...###"),
        SETTINGS(".#....#../.#....#../###...#../###...#../.#...###./.#...###./.#....#../.#....#../.#....#.."),
        NEXT("...#...../...##..../...###.../########./#########/########./...###.../...##..../...#....."),
        PREVIOUS(".....#.../....##.../...###.../.########/#########/.########/...###.../....##.../.....#..."),
        PLAY("..#....../..##...../..###..../..####.../..#####../..####.../..###..../..##...../..#......"),
        STOP("........./.#######./.#######./.#######./.#######./.#######./.#######./.#######./........."),
        SHUFFLE("......#../##....##./.##..####/..####.../...##..../..####.../.##..####/##....##./......#.."),
        REPEAT("......#../..######./.##...###/.#......./.#.....#./.......#./###...##./.######../..#......"),
        SEARCH(".####..../##..##.../#....#.../#....#.../##..##.../.####..../.....##../......##./.......##"),
        SORT("..#...###/..#...##./..#...#../..#....../#####.###/.###..##./..#...#../........./........."),
        COUNT("......##./......##./...##.##./...##.##./##.##.##./##.##.##./##.##.##./########./........."),
        MOD("...##..../.#######./.##..###./###..####/#########/.###..##./.###..##./.#######./....##..."),
        MEMORY("#########/##....###/##....###/#########/##....###/#.......#/#.#####.#/#.#####.#/#########"),
        ITEMS("####.####/#..#.#..#/#..#.#..#/####.####/........./####.####/#..#.#..#/#..#.#..#/####.####"),
        GEAR("...###.../.#.###.#./.#######./###...###/###.#.###/###...###/.#######./.#.###.#./...###..."),
        GENERIC("...###.../..#...#../.#.....#./#...#...#/#..###..#/#...#...#/.#.....#./..#...#../...###..."),
        FILTER_ALLOW(filterBadge(true)),
        FILTER_BLOCK(filterBadge(false)),
        FILTER_CONTENTS(contentsGlyph()),
        MATCH_ITEM(appleGlyph()),
        MATCH_MOD(letterGlyph(false)),
        MATCH_TAGS(tagGlyph()),
        MATCH_DAMAGE(damageGlyph(false)),
        IGNORE_DAMAGE(damageGlyph(true)),
        MATCH_COMPONENTS(componentsGlyph(false)),
        IGNORE_COMPONENTS(componentsGlyph(true));
        private final int[][] spans;
        private final int[][] coloredSpans;
        Icon(int[] pixels) {
            spans = new int[0][];
            coloredSpans = colorRuns(pixels);
        }
        Icon(String pattern) {
            coloredSpans = null;
            String[] rows = pattern.split("/");
            if (rows.length != 9) throw new IllegalArgumentException("Icon requires nine rows");
            var runs = new java.util.ArrayList<int[]>();
            for (int y = 0; y < 9; y++) {
                if (rows[y].length() != 9) throw new IllegalArgumentException("Icon requires nine columns");
                for (int x = 0; x < 9;) {
                    int start = x;
                    while (x < 9 && rows[y].charAt(x) == '#') x++;
                    if (x > start) runs.add(new int[]{start, y, x - start}); else x++;
                }
            }
            spans = runs.toArray(int[][]::new);
        }
    }
    private Icon icon;
    private boolean selected;
    private boolean embedded;
    private ItemStack item = ItemStack.EMPTY;
    public BackpackIconButton(int x, int y, int width, int height, Component label, Icon icon, Runnable action) {
        super(x, y, width, height, label, press(action), DEFAULT_NARRATION);
        this.icon = Objects.requireNonNull(icon);
        setTooltip(Tooltip.create(label));
    }
    private static OnPress press(Runnable action) {
        Objects.requireNonNull(action);
        return ignored -> action.run();
    }
    public BackpackIconButton setSelected(boolean value) { selected = value; return this; }
    public BackpackIconButton setEmbedded(boolean value) { embedded = value; return this; }
    public boolean isSelected() { return selected; }
    public BackpackIconButton setIcon(Icon value) { icon = Objects.requireNonNull(value); return this; }
    public Icon getIcon() { return icon; }
    public BackpackIconButton setItem(ItemStack value) { item = value == null ? ItemStack.EMPTY : value.copy(); return this; }
    @Override public void setMessage(Component label) { super.setMessage(label); setTooltip(Tooltip.create(label)); }
    @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (alpha <= 0 || getWidth() <= 2 || getHeight() <= 2) return;
        boolean hot = active && isHoveredOrFocused();
        int fill, light, dark, edge;
        if (!active) {
            fill = 0xFF6F6256; light = 0xFF968773; dark = 0xFF493D33; edge = 0xFF33291F;
        } else if (icon.coloredSpans != null && item.isEmpty()) {
            fill = hot ? 0xFFF7B849 : 0xFFECA535;
            light = 0xFFFCE688; dark = 0xFFD58704; edge = 0xFF744832;
        } else if (item.isEmpty() && icon == Icon.SORT_ORDER) {
            fill = hot ? 0xFF4B3824 : 0xFF35271A;
            light = 0xFFFBBC59; dark = 0xFFA45C0C; edge = 0xFFB86F02;
        } else if (item.isEmpty() && (icon == Icon.TRANSFER_UP || icon == Icon.TRANSFER_DOWN)) {
            fill = hot ? 0xFFF7B849 : 0xFFECA535;
            light = 0xFFFBCE75; dark = 0xFFB86F02; edge = 0xFF8D4D0E;
        } else if (!item.isEmpty() || icon == Icon.GEAR || icon == Icon.SETTINGS) {
            fill = hot ? 0xFFD0B393 : 0xFFBDA285;
            light = 0xFFEFCEA5; dark = 0xFF7D634B; edge = 0xFF3B2C1F;
        } else if (icon == Icon.SEARCH) {
            fill = hot ? 0xFF918980 : 0xFF7B7770;
            light = 0xFFB5AA96; dark = 0xFF4C463E; edge = 0xFF29231F;
        } else {
            fill = hot ? 0xFFBF713F : 0xFFA6552F;
            light = 0xFFD18C58; dark = 0xFF653117; edge = 0xFF220F06;
        }
        if (selected && active && (icon.coloredSpans == null || !item.isEmpty())) { light = 0xFFFFD375; edge = 0xFFF2CA65; }
        if (!embedded) {
            BackpackStyle.bevel(g, getX(), getY(), getWidth(), getHeight(),
                    fade(fill), fade(light), fade(dark), fade(edge));
            if (icon.coloredSpans != null && item.isEmpty()) {
                int rim = fade(active ? 0xFFFFFFFF : 0xFF968773);
                g.fill(getX(), getY(), getRight() - 1, getY() + 1, rim);
                g.fill(getX(), getY() + 1, getX() + 1, getBottom() - 1, rim);
            }
        }
        g.enableScissor(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1);
        try {
            if (item.isEmpty()) drawGlyph(g); else drawItem(g);
        } finally { g.disableScissor(); }
    }
    private int fade(int color) {
        return ((int) ((color >>> 24) * Math.clamp(alpha, 0.0F, 1.0F)) << 24) | (color & 0xFFFFFF);
    }
    private void drawItem(GuiGraphicsExtractor g) {
        int size = Math.min(16, Math.min(getWidth(), getHeight()) - 2);
        g.pose().pushMatrix();
        try {
            g.pose().translate(getX() + (getWidth() - size) / 2.0F, getY() + (getHeight() - size) / 2.0F);
            g.pose().scale(size / 16.0F, size / 16.0F);
            g.fakeItem(item, 0, 0);
        } finally { g.pose().popMatrix(); }
    }
    private void drawGlyph(GuiGraphicsExtractor g) {
        if (icon.coloredSpans != null) {
            int x = getX() + (getWidth() - 14) / 2, y = getY() + (getHeight() - 14) / 2;
            for (int[] span : icon.coloredSpans)
                g.fill(x + span[0], y + span[1], x + span[0] + span[2], y + span[1] + 1, symbolColor(span[3]));
            return;
        }
        int x = getX() + (getWidth() - 10) / 2, y = getY() + (getHeight() - 10) / 2;
        int color = switch (icon) {
            case TRANSFER_UP -> 0xFF82B733;
            case TRANSFER_DOWN -> 0xFFCA371A;
            case SORT_ORDER -> 0xFFFFD351;
            case SEARCH -> 0xFFE2DED4;
            case GEAR, SETTINGS -> 0xFF514639;
            default -> 0xFFFFDFA1;
        };
        int light = fade(active ? color : 0xFFAA9783), shadow = fade(0xFF3C2418);
        for (int[] span : icon.spans) g.fill(x + span[0] + 1, y + span[1] + 1, x + span[0] + span[2] + 1, y + span[1] + 2, shadow);
        for (int[] span : icon.spans) g.fill(x + span[0], y + span[1], x + span[0] + span[2], y + span[1] + 1, light);
    }
    // Original fourteen-pixel symbols, assembled once into horizontal color runs.
    private static int[] filterBadge(boolean allow) {
        int[] pixels = new int[14 * 14];
        for (int y = 0; y < 14; y++) for (int x = 0; x < 14; x++) {
            int dx = 2 * x - 13, dy = 2 * y - 13, radius = dx * dx + dy * dy;
            if (radius > 177) continue;
            int color = radius >= 137 ? allow ? 0xFF264D14 : 0xFF5E0114
                    : x + y < 12 ? allow ? 0xFF83C83B : 0xFFBD2A02
                    : allow ? 0xFF4D951B : 0xFFAA0F01;
            pixels[y * 14 + x] = color;
        }
        if (allow) {
            check(pixels, 1, 1, 0xFF264D14);
            check(pixels, 0, 0, 0xFFE9DFD6);
        } else {
            for (int offset = 1; offset >= 0; offset--) for (int i = 0; i < 7; i++) {
                int color = offset == 0 ? 0xFFC4C4C4 : 0xFF6A6665;
                rect(pixels, 3 + i + offset, 3 + i + offset, 2, 2, color);
                rect(pixels, 9 - i + offset, 3 + i + offset, 2, 2, color);
            }
            rect(pixels, 3, 3, 2, 1, 0xFFE9DFD6);
            rect(pixels, 9, 3, 2, 1, 0xFFE9DFD6);
        }
        return pixels;
    }
    private static void check(int[] pixels, int x, int y, int color) {
        for (int i = 0; i < 3; i++) rect(pixels, x + 2 + i, y + 6 + i, 2, 2, color);
        for (int i = 0; i < 6; i++) rect(pixels, x + 4 + i, y + 8 - i, 2, 2, color);
    }
    private static int[] contentsGlyph() {
        int[] pixels = new int[14 * 14];
        rect(pixels, 1, 3, 12, 4, 0xFF52473D);
        rect(pixels, 2, 4, 10, 2, 0xFFB87412);
        rect(pixels, 2, 7, 10, 6, 0xFF52473D);
        rect(pixels, 3, 8, 8, 4, 0xFF744832);
        rect(pixels, 3, 8, 8, 1, 0xFFD58704);
        rect(pixels, 6, 5, 2, 4, 0xFFFCE688);
        rect(pixels, 3, 10, 2, 2, 0xFF83C83B);
        rect(pixels, 6, 10, 2, 2, 0xFF73BDE0);
        rect(pixels, 9, 10, 2, 2, 0xFFDD1725);
        return pixels;
    }
    private static int[] appleGlyph() {
        int[] pixels = new int[14 * 14];
        rect(pixels, 7, 0, 2, 4, 0xFF744832);
        rect(pixels, 8, 0, 1, 2, 0xFFAE957B);
        rect(pixels, 3, 3, 3, 2, 0xFF790D13);
        rect(pixels, 8, 3, 3, 2, 0xFF790D13);
        rect(pixels, 2, 4, 10, 7, 0xFF790D13);
        rect(pixels, 1, 6, 12, 3, 0xFF790D13);
        rect(pixels, 3, 11, 8, 1, 0xFF790D13);
        rect(pixels, 4, 12, 6, 1, 0xFF790D13);
        rect(pixels, 3, 4, 3, 1, 0xFFB4131E);
        rect(pixels, 8, 4, 3, 1, 0xFFB4131E);
        rect(pixels, 2, 5, 10, 4, 0xFFB4131E);
        rect(pixels, 3, 9, 8, 2, 0xFFB4131E);
        rect(pixels, 4, 11, 6, 1, 0xFF9C1017);
        rect(pixels, 9, 6, 3, 3, 0xFF9C1017);
        rect(pixels, 3, 5, 4, 3, 0xFFDD1725);
        rect(pixels, 3, 5, 3, 2, 0xFFFF1C2B);
        rect(pixels, 3, 5, 2, 1, 0xFFFF969D);
        return pixels;
    }
    private static int[] letterGlyph(boolean components) {
        int[] pixels = new int[14 * 14];
        for (int offset = 1; offset >= 0; offset--) {
            int color = offset == 0 ? 0xFF25211F : 0xFF744832;
            rect(pixels, 3 + offset, 2 + offset, 2, 10, color);
            rect(pixels, 9 + offset, 2 + offset, 2, 10, color);
            if (components) {
                for (int row = 0; row < 8; row++)
                    rect(pixels, 4 + row * 5 / 8 + offset, 3 + row + offset, 2, 1, color);
            } else {
                for (int i = 0; i < 4; i++) {
                    rect(pixels, 3 + i + offset, 2 + i + offset, 2, 2, color);
                    rect(pixels, 9 - i + offset, 2 + i + offset, 2, 2, color);
                }
            }
        }
        rect(pixels, 3, 2, 2, 1, 0xFF52473D);
        rect(pixels, 9, 2, 2, 1, 0xFF52473D);
        return pixels;
    }
    private static int[] tagGlyph() {
        int[] pixels = new int[14 * 14];
        rect(pixels, 1, 2, 7, 10, 0xFF744832);
        rect(pixels, 8, 3, 2, 8, 0xFF744832);
        rect(pixels, 10, 5, 2, 4, 0xFF744832);
        rect(pixels, 12, 6, 1, 2, 0xFF744832);
        rect(pixels, 2, 3, 6, 8, 0xFFFCE688);
        rect(pixels, 8, 4, 1, 6, 0xFFFCE688);
        rect(pixels, 9, 5, 1, 4, 0xFFE9DFD6);
        rect(pixels, 10, 6, 1, 2, 0xFFE9DFD6);
        rect(pixels, 3, 5, 2, 2, 0xFF744832);
        rect(pixels, 6, 7, 1, 3, 0xFF744832);
        rect(pixels, 8, 7, 1, 2, 0xFF744832);
        return pixels;
    }
    private static int[] damageGlyph(boolean ignored) {
        int[] pixels = new int[14 * 14];
        rect(pixels, 2, 3, 10, 4, 0xFF101010);
        rect(pixels, 2, 8, 10, 4, 0xFF101010);
        rect(pixels, 3, 4, 8, 2, 0xFF9BFF00);
        rect(pixels, 3, 9, 8, 2, 0xFF4B6B1A);
        rect(pixels, 3, 9, 5, 2, 0xFF9BFF00);
        if (ignored) prohibition(pixels);
        return pixels;
    }
    private static int[] componentsGlyph(boolean ignored) {
        int[] pixels = letterGlyph(true);
        if (ignored) prohibition(pixels);
        return pixels;
    }
    private static void prohibition(int[] pixels) {
        for (int y = 0; y < 14; y++) for (int x = 0; x < 14; x++) {
            int dx = 2 * x - 13, dy = 2 * y - 13, radius = dx * dx + dy * dy;
            if (radius > 177) continue;
            if (radius >= 81) pixels[y * 14 + x] = radius >= 137 ? 0xFF5E0114
                    : x + y < 12 ? 0xFFBD2A02 : 0xFFAA0F01;
            int slash = Math.abs(x + y - 13);
            if (radius < 137 && slash <= 2)
                pixels[y * 14 + x] = slash == 2 ? 0xFF7D010C : 0xFFAA0F01;
        }
    }
    private static void rect(int[] pixels, int x, int y, int width, int height, int color) {
        if (x < 0 || y < 0 || width < 1 || height < 1 || x + width > 14 || y + height > 14)
            throw new IllegalArgumentException("Filter symbol exceeds its fourteen-pixel canvas");
        for (int row = y; row < y + height; row++)
            java.util.Arrays.fill(pixels, row * 14 + x, row * 14 + x + width, color);
    }
    private static int[][] colorRuns(int[] pixels) {
        if (pixels.length != 14 * 14) throw new IllegalArgumentException("Filter symbol requires fourteen rows and columns");
        var runs = new java.util.ArrayList<int[]>();
        for (int y = 0; y < 14; y++) for (int x = 0; x < 14;) {
            int color = pixels[y * 14 + x], start = x++;
            while (x < 14 && pixels[y * 14 + x] == color) x++;
            if (color != 0) runs.add(new int[]{start, y, x - start, color});
        }
        return runs.toArray(int[][]::new);
    }
    private int symbolColor(int color) {
        if (!active) {
            int luminance = (77 * ((color >>> 16) & 255) + 150 * ((color >>> 8) & 255) + 29 * (color & 255)) >>> 8;
            int gray = (luminance + 128) / 2;
            color = 0xFF000000 | (gray << 16) | ((gray * 15 / 16) << 8) | (gray * 7 / 8);
        }
        return fade(color);
    }
}
