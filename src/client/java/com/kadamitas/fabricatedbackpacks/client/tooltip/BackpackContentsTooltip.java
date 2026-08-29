package com.kadamitas.fabricatedbackpacks.client.tooltip;

import com.kadamitas.fabricatedbackpacks.item.BackpackTooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Locale;

/** A noninteractive, count-preserving snapshot; its cells are never inventory slots. */
final class BackpackContentsTooltip implements ClientTooltipComponent {
    private static final int CELL = 18;
    private final ItemStack[] items;
    private final int columns;
    private final int rows;
    private final boolean expanded;
    private final boolean hasLargeCount;
    private final float gridScale;
    private final Component summary;
    private final Component hint;
    private final Component countHint;

    BackpackContentsTooltip(BackpackTooltip snapshot) {
        Minecraft client = Minecraft.getInstance();
        expanded = client.hasShiftDown();
        columns = snapshot.columns();
        rows = Math.ceilDiv(snapshot.contents().size(), columns);
        summary = Component.translatable("tooltip.fabricated_backpacks.contents_summary",
                NumberFormat.getIntegerInstance(Locale.ROOT).format(snapshot.itemCount()),
                snapshot.occupiedSlots(), snapshot.contents().size());
        hint = Component.translatable(snapshot.occupiedSlots() == 0
                ? "tooltip.fabricated_backpacks.contents_empty" : "tooltip.fabricated_backpacks.contents_hint");
        countHint = Component.translatable("tooltip.fabricated_backpacks.contents_counts");
        items = new ItemStack[snapshot.contents().size()];
        Arrays.fill(items, ItemStack.EMPTY);
        boolean large = false;
        for (var entry : snapshot.contents().entries()) {
            items[entry.slot()] = entry.create();
            large |= entry.count() >= 1_000;
        }
        hasLargeCount = large;
        // Keep all cells on screen at small GUI sizes without changing their order.
        int room = Math.max(60, client.getWindow().getGuiScaledHeight() - 150);
        gridScale = rows == 0 ? 1F : Math.min(1F, room / (float) (rows * CELL));
    }

    @Override public int getHeight(Font font) {
        if (!expanded) return font.lineHeight + 3;
        return font.lineHeight + 5 + (int) Math.ceil(rows * CELL * gridScale)
                + (hasLargeCount ? font.lineHeight + 3 : 0);
    }

    @Override public int getWidth(Font font) {
        if (!expanded) return font.width(hint);
        int width = Math.max(font.width(summary), (int) Math.ceil(columns * CELL * gridScale));
        return hasLargeCount ? Math.max(width, font.width(countHint)) : width;
    }

    @Override public void extractText(GuiGraphicsExtractor graphics, Font font, int x, int y) {
        graphics.text(font, expanded ? summary : hint, x, y, expanded ? 0xFFE0D7C8 : 0xFF9CABA7, false);
        if (expanded && hasLargeCount) graphics.text(font, countHint, x,
                y + font.lineHeight + 5 + (int) Math.ceil(rows * CELL * gridScale), 0xFF9CABA7, false);
    }

    @Override public void extractImage(Font font, int x, int y, int width, int height, GuiGraphicsExtractor graphics) {
        if (!expanded || items.length == 0) return;
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(x, y + font.lineHeight + 3);
            graphics.pose().scale(gridScale);
            for (int slot = 0; slot < items.length; slot++) {
                int left = slot % columns * CELL;
                int top = slot / columns * CELL;
                graphics.fill(left, top, left + CELL - 1, top + CELL - 1, 0xFF283239);
                graphics.outline(left, top, CELL - 1, CELL - 1, 0xFF53606A);
                ItemStack stack = items[slot];
                if (stack.isEmpty()) continue;
                graphics.fakeItem(stack, left + 1, top + 1);
                graphics.itemDecorations(font, stack, left + 1, top + 1, "");
                if (stack.getCount() > 1) drawCount(graphics, font, Integer.toString(stack.getCount()), left, top);
            }
        } finally {
            graphics.pose().popMatrix();
        }
    }

    private static void drawCount(GuiGraphicsExtractor graphics, Font font, String label, int x, int y) {
        int width = font.width(label);
        float scale = Math.min(1F, 15F / Math.max(1, width));
        graphics.pose().pushMatrix();
        try {
            graphics.pose().translate(x + 17F - width * scale, y + 17F - font.lineHeight * scale);
            graphics.pose().scale(scale);
            graphics.text(font, label, 0, 0, 0xFFFFFFFF, true);
        } finally {
            graphics.pose().popMatrix();
        }
    }

}
