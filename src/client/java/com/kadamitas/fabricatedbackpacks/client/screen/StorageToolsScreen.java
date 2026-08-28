package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Bulk settings and inventory actions share the same server-authorized backpack session. */
public final class StorageToolsScreen extends Screen {
    private final Screen previous;
    private final BackpackMenu menu;
    private int left, top;

    StorageToolsScreen(Screen previous, BackpackMenu menu) {
        super(Component.literal("Storage tools"));
        this.previous = previous;
        this.menu = menu;
    }
    @Override protected void init() {
        left = (width - 312) / 2;
        top = Math.max(2, (height - 228) / 2);
        button("Remember occupied", 12, 30, 142, () -> menuButton(6));
        button("Clear all memory", 158, 30, 142, () -> menuButton(7));
        button("Exclude all from sort", 12, 54, 142, () -> menuButton(8));
        button("Clear sort exclusions", 158, 54, 142, () -> menuButton(9));
        String[] sorts = {"Name", "Count", "Mod", "Tags"};
        int[] actions = {0, 3, 4, 5};
        for (int index = 0; index < sorts.length; index++) {
            final int action = actions[index];
            button(sorts[index], 12 + index * 73, 91, 69, () -> menuButton(action));
        }
        var color = addRenderableWidget(new EditBox(font, left + 12, top + 132, 100, 18, Component.literal("No-sort overlay color")));
        color.setMaxLength(7);
        color.setValue("#%06X".formatted(menu.bag().settings().getIntOr("no_sort_color", 0xdb8c39) & 0xffffff));
        button("Set overlay color", 116, 132, 184, () -> send("no_sort_color", 0, color.getValue()));
        button("Store matching", 12, 159, 142, () -> send("bulk_store", minecraft.hasShiftDown() ? 1 : 0, ""))
                .setTooltip(Tooltip.create(Component.literal("Move matching main-inventory stacks into this bag. Shift: all eligible stacks. Hotbar stays put.")));
        button("Take matching", 158, 159, 142, () -> send("bulk_take", minecraft.hasShiftDown() ? 1 : 0, ""))
                .setTooltip(Tooltip.create(Component.literal("Move matching bag stacks to main inventory. Shift: all eligible stacks. Exclusions and infinite seeds stay put.")));
        button("Back", 12, 198, 288, this::onClose);
    }
    private Button button(String text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), ignored -> action.run()).bounds(left + x, top + y, width, 18).build());
    }
    private void menuButton(int action) { minecraft.gameMode.handleInventoryButtonClick(menu.containerId, action); }
    private void send(String action, int value, String text) { ClientPlayNetworking.send(new MenuAction(menu.containerId, action, 0, value, text)); }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.player.containerMenu != menu || !minecraft.player.isAlive()) minecraft.gui.setScreen(null);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(left, top, left + 312, top + 228, 0xffccb996);
        graphics.outline(left, top, 312, 228, 0xff493326);
        graphics.text(font, title, left + 12, top + 10, 0xff302a21, false);
        graphics.text(font, "Sort", left + 12, top + 79, 0xff302a21, false);
        graphics.text(font, "No-sort overlay: RGB hex color", left + 12, top + 120, 0xff302a21, false);
        graphics.text(font, "Hold Shift when transferring to move all", left + 12, top + 183, 0xff302a21, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
    @Override public void onClose() { minecraft.gui.setScreen(minecraft.player != null && minecraft.player.containerMenu == menu ? previous : null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}
