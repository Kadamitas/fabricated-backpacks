package com.kadamitas.fabricatedbackpacks.client.automation;

import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineMenu;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackIconButton;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Four real machine slots; gauges are read-only projections of native menu data. */
public final class SteamEngineScreen extends AbstractContainerScreen<SteamEngineMenu> {
    private BackpackIconButton enabled;
    public SteamEngineScreen(SteamEngineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, SteamEngineMenu.WIDTH, SteamEngineMenu.HEIGHT);
    }
    @Override protected void init() {
        super.init();
        enabled = addRenderableWidget(new BackpackIconButton(leftPos + 80, topPos + 54, 16, 16,
                enabledLabel(), BackpackIconButton.Icon.POWER, () -> {
            if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 0);
        }));
        updateControl();
    }
    private Component enabledLabel() { return text(menu.enabled() ? "enabled" : "disabled"); }
    private void updateControl() {
        if (enabled != null) { enabled.setMessage(enabledLabel()); enabled.setSelected(menu.enabled()); }
    }
    @Override protected void containerTick() { super.containerTick(); updateControl(); }

    @Override public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);
        BackpackStyle.frame(g, leftPos, topPos, imageWidth, imageHeight, BackpackStyle.Surface.BODY);
        BackpackStyle.frame(g, leftPos + 3, topPos + 3, imageWidth - 6, 13, BackpackStyle.Surface.TITLE);
        BackpackStyle.frame(g, leftPos + 7, topPos + 20, imageWidth - 14, 55, BackpackStyle.Surface.PANEL);
        BackpackStyle.frame(g, leftPos + 3, topPos + 79, imageWidth - 6, 12, BackpackStyle.Surface.TITLE);
        if (isHovering(8, 3, imageWidth - 16, 13, mouseX, mouseY))
            setWrappedTooltip(g, title, mouseX, mouseY);
        for (var slot : menu.slots) BackpackStyle.slot(g, leftPos + slot.x, topPos + slot.y, false);
        gauge(g, 14, 23, 16, 47, menu.waterDroplets(), menu.waterCapacityDroplets(), 0xFF438DB8);
        gauge(g, 146, 23, 16, 47, menu.energy(), menu.energyCapacity(), 0xFFD6AC43);
        int arrowX = leftPos + 77, arrowY = topPos + 28;
        g.fill(arrowX, arrowY + 3, arrowX + 18, arrowY + 6, 0xFF685747);
        for (int row = 0; row < 7; row++) {
            int length = 4 - Math.abs(3 - row);
            g.fill(arrowX + 16, arrowY + row + 1, arrowX + 16 + length, arrowY + row + 2, 0xFF685747);
        }
        int lamp = menu.active() ? 0xFF73B955 : 0xFF57483A;
        g.fill(leftPos + 83, topPos + 42, leftPos + 93, topPos + 47, 0xFF3E3024);
        g.fill(leftPos + 84, topPos + 43, leftPos + 92, topPos + 46, lamp);
        int burn = menu.burnDuration() <= 0 ? 0 : (int) Math.clamp(48L * menu.burnRemaining() / menu.burnDuration(), 0, 48);
        g.fill(leftPos + 64, topPos + 73, leftPos + 112, topPos + 76, 0xFF504237);
        if (burn > 0) g.fill(leftPos + 64, topPos + 73, leftPos + 64 + burn, topPos + 75, 0xFFDC9B43);
        if (isHovering(14, 23, 16, 47, mouseX, mouseY)) setWrappedTooltip(g,
                text("water", millibuckets(menu.waterDroplets()), millibuckets(menu.waterCapacityDroplets())), mouseX, mouseY);
        if (isHovering(146, 23, 16, 47, mouseX, mouseY)) setWrappedTooltip(g,
                text("energy", count(menu.energy()), count(menu.energyCapacity())), mouseX, mouseY);
        if (isHovering(80, 40, 16, 9, mouseX, mouseY)) setWrappedTooltip(g, text(menu.active() ? "working" : "idle"), mouseX, mouseY);
        if (isHovering(64, 72, 48, 5, mouseX, mouseY)) setWrappedTooltip(g,
                text("burn", count(menu.burnRemaining()), count(menu.burnDuration())), mouseX, mouseY);
        String[] labels = {"fuel", "water_input", "fuel_remainder", "water_remainder"};
        for (int index = 0; index < 4; index++) {
            var slot = menu.slots.get(index);
            if (!slot.hasItem() && isHovering(slot.x, slot.y, 16, 16, mouseX, mouseY))
                setWrappedTooltip(g, text(labels[index]), mouseX, mouseY);
        }
    }
    @Override protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int titleWidth = imageWidth - 16;
        var line = font.width(title) > titleWidth
                ? ComponentRenderUtils.clipText(title, font, titleWidth) : title.getVisualOrderText();
        FormattedCharSequence visible = sink -> line.accept((index, style, codePoint) ->
                sink.accept(index, style.withoutShadow(), codePoint));
        // Glyph overhang may exceed advance width; keep it inside the header too.
        // The original title remains unchanged for the hover tooltip and native screen narration.
        g.enableScissor(8, 4, 8 + titleWidth, 16);
        try {
            g.text(font, visible, 8, 6, BackpackStyle.TITLE_TEXT, false);
        } finally {
            g.disableScissor();
        }
        g.text(font, playerInventoryTitle, 8, 81, BackpackStyle.TITLE_TEXT, false);
    }
    private void gauge(GuiGraphicsExtractor g, int x, int y, int width, int height, long amount, long capacity, int color) {
        int gx = leftPos + x, gy = topPos + y;
        g.fill(gx - 1, gy - 1, gx + width + 1, gy + height + 1, 0xFF3E3024);
        g.fill(gx, gy, gx + width, gy + height, 0xFF302E2B);
        int fill = amount <= 0 || capacity <= 0 ? 0 : Math.max(1, (int) Math.min(height, height * (amount / (double) capacity)));
        if (fill > 0) {
            g.fill(gx, gy + height - fill, gx + width, gy + height, color);
            g.fill(gx + 1, gy + height - fill, gx + 2, gy + height, 0x667FFFFF);
        }
        for (int offset = 9; offset < height; offset += 9) g.fill(gx, gy + offset, gx + 3, gy + offset + 1, 0xFFBCAA87);
    }
    static Component text(String key, Object... arguments) { return Component.translatable("automation.fabricated_backpacks." + key, arguments); }
    private void setWrappedTooltip(GuiGraphicsExtractor graphics, Component message, int mouseX, int mouseY) {
        int maxWidth = Math.max(1, Math.min(260, width - 24));
        graphics.setTooltipForNextFrame(font, font.split(message, maxWidth), mouseX, mouseY);
    }
    private static String count(long value) { return String.format(Locale.ROOT, "%,d", value); }
    private static String millibuckets(long droplets) {
        return new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(droplets / 81.0);
    }
}
