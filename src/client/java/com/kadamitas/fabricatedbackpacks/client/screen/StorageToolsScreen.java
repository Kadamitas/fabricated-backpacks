package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Bulk settings and inventory actions share the same server-authorized backpack session. */
public final class StorageToolsScreen extends Screen {
    private final Screen previous;
    private final BackpackMenu menu;
    private final ShiftTooltips tooltips = new ShiftTooltips();
    private int left, top;

    StorageToolsScreen(Screen previous, BackpackMenu menu) {
        super(Component.literal("Storage tools"));
        this.previous = previous;
        this.menu = menu;
    }
    @Override protected void init() {
        left = (width - 312) / 2;
        top = Math.max(2, (height - 228) / 2);
        tooltips.clear();
        explain(button("Remember occupied", 12, 30, 142, () -> menuButton(6)),
                "Remember the current item type in every occupied storage slot. Remembered empty slots accept only their matching item.");
        explain(button("Clear all memory", 158, 30, 142, () -> menuButton(7)),
                "Remove every remembered slot assignment from this backpack without moving or deleting stored items.");
        explain(button("Exclude all from sort", 12, 54, 142, () -> menuButton(8)),
                "Mark every storage slot as fixed so sorting leaves its contents in place.");
        explain(button("Clear sort exclusions", 158, 54, 142, () -> menuButton(9)),
                "Allow sorting to move every storage slot again. This does not clear remembered item assignments.");
        String[] sorts = {"Name", "Count", "Mod", "Tags"};
        int[] actions = {0, 3, 4, 5};
        String[] sortHelp = {
                "Sort movable storage slots alphabetically by item name.",
                "Sort movable storage slots by total stack count.",
                "Sort movable storage slots by the item's mod namespace, then by item name.",
                "Sort movable storage slots by item tags, then by item name."
        };
        for (int index = 0; index < sorts.length; index++) {
            final int action = actions[index];
            explain(button(sorts[index], 12 + index * 73, 91, 69, () -> menuButton(action)), sortHelp[index]);
        }
        var color = explain(addRenderableWidget(new EditBox(font, left + 12, top + 132, 100, 18, Component.literal("No-sort overlay color"))),
                "Enter the six-digit RGB hex color used to mark no-sort slots, for example #DB8C39.");
        color.setMaxLength(7);
        color.setValue("#%06X".formatted(menu.bag().settings().getIntOr("no_sort_color", 0xdb8c39) & 0xffffff));
        explain(button("Set overlay color", 116, 132, 184, () -> send("no_sort_color", 0, color.getValue())),
                "Apply the entered RGB color to the overlay drawn on storage slots excluded from sorting.");
        explain(button("Store matching", 12, 159, 142, () -> send("bulk_store", minecraft.hasShiftDown() ? 1 : 0, "")),
                "Move matching main-inventory stacks into this backpack. While Shift is held, move all eligible stacks; the hotbar stays put.");
        explain(button("Take matching", 158, 159, 142, () -> send("bulk_take", minecraft.hasShiftDown() ? 1 : 0, "")),
                "Move matching backpack stacks into your main inventory. While Shift is held, move all eligible stacks; exclusions and infinite seeds stay put.");
        explain(button("Back", 12, 198, 288, this::onClose), "Return to backpack settings. Completed actions are already applied.");
        tooltips.refresh(minecraft);
    }
    private Button button(String text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), ignored -> action.run()).bounds(left + x, top + y, width, 18).build());
    }
    private <T extends net.minecraft.client.gui.components.AbstractWidget> T explain(T widget, String help) {
        return tooltips.add(widget, help);
    }
    private void menuButton(int action) { minecraft.gameMode.handleInventoryButtonClick(menu.containerId, action); }
    private void send(String action, int value, String text) { ClientPlayNetworking.send(new MenuAction(menu.containerId, action, 0, value, text)); }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.player.containerMenu != menu || !minecraft.player.isAlive()) minecraft.gui.setScreen(null);
        else tooltips.refresh(minecraft);
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
