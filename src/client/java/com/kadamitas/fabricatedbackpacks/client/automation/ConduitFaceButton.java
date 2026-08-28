package com.kadamitas.fabricatedbackpacks.client.automation;

import com.kadamitas.fabricatedbackpacks.client.screen.BackpackStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

/** Six world-axis spokes distinguish faces without abbreviating their narrated names. */
final class ConduitFaceButton extends Button {
    private final Direction face;
    private boolean selected;
    ConduitFaceButton(int x, int y, Component label, Direction face, Runnable action) {
        super(x, y, 20, 20, label, ignored -> action.run(), DEFAULT_NARRATION);
        this.face = face;
        setTooltip(Tooltip.create(label));
    }
    void selected(boolean value) { selected = value; }
    @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        BackpackStyle.frame(g, getX(), getY(), getWidth(), getHeight(), BackpackStyle.Surface.PANEL);
        if (selected || isHoveredOrFocused()) g.renderOutline(getX(), getY(), getWidth(), getHeight(), selected ? 0xFFFFD56A : 0xFFEBD4A7);
        paintGlyph(g, getX(), getY(), face);
    }
    static void paintGlyph(GuiGraphics g, int x, int y, Direction face) {
        for (Direction ray : Direction.values()) {
            int[] end = endpoint(ray);
            line(g, x, y, 10, 10, end[0], end[1], 0xFF786A5A);
        }
        int[] end = endpoint(face);
        line(g, x, y, 10, 10, end[0], end[1], 0xFF235D62);
        g.fill(x + end[0] - 1, y + end[1] - 1, x + end[0] + 2, y + end[1] + 2, 0xFF1F626B);
        g.fill(x + 9, y + 9, x + 12, y + 12, 0xFFE8D298);
    }
    private static int[] endpoint(Direction direction) {
        return switch (direction) {
            case DOWN -> new int[]{10, 16}; case UP -> new int[]{10, 3};
            case NORTH -> new int[]{15, 6}; case SOUTH -> new int[]{5, 14};
            case WEST -> new int[]{5, 6}; case EAST -> new int[]{15, 14};
        };
    }
    private static void line(GuiGraphics g, int originX, int originY, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0), dy = Math.abs(y1 - y0), sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int error = dx - dy;
        while (true) {
            g.fill(originX + x0, originY + y0, originX + x0 + 1, originY + y0 + 1, color);
            if (x0 == x1 && y0 == y1) break;
            int twice = 2 * error;
            if (twice > -dy) { error -= dy; x0 += sx; }
            if (twice < dx) { error += dx; y0 += sy; }
        }
    }
}
