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
        GENERIC("...###.../..#...#../.#.....#./#...#...#/#..###..#/#...#...#/.#.....#./..#...#../...###...");
        private final int[][] spans;
        Icon(String pattern) {
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
        if (selected && active) { light = 0xFFFFD375; edge = 0xFFF2CA65; }
        if (!embedded) BackpackStyle.bevel(g, getX(), getY(), getWidth(), getHeight(),
                fade(fill), fade(light), fade(dark), fade(edge));
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
}
