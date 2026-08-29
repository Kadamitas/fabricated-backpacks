package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Per-backpack preferences and private settings templates, backed by server-validated actions. */
public final class BackpackSettingsScreen extends Screen {
    private final BackpackScreen previous;
    private final BackpackMenu menu;
    private EditBox name, templateName, displaySlot;
    private final List<Toggle> toggles = new ArrayList<>();
    private final ShiftTooltips tooltips = new ShiftTooltips();
    private int left, top;
    private String savedTemplateName = "1";
    private record Toggle(String setting, String label, boolean initial, Button button) {}

    public BackpackSettingsScreen(BackpackScreen previous) {
        super(Component.literal("Backpack settings"));
        this.previous = previous;
        menu = previous.getMenu();
    }
    @Override protected void init() {
        left = (width - 360) / 2;
        top = Math.max(4, (height - 244) / 2);
        toggles.clear();
        tooltips.clear();
        explain(button("Edit slots", 118, 5, 70, () -> {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, 2);
            onClose();
        }), "Cycle storage-slot editing between item memory, sorting exclusions, and normal interaction. Memory reserves an item type for a slot; no-sort keeps that slot in place.");
        explain(button("Equipment", 194, 5, 76, () -> ClientPlayNetworking.send(new MenuAction(-1, "equipment", 0, 0, ""))),
                "Open the backpack equipment screen to equip or remove the backpack worn on your back.");
        explain(button("Slot tools", 274, 5, 74, () -> minecraft.gui.setScreen(new StorageToolsScreen(this, menu))),
                "Open bulk memory, sorting, overlay-color, and matching-transfer controls.");
        name = explain(field("Backpack name", 12, 26, 258, menu.bag().stack().getHoverName().getString(), 50),
                "Enter a custom backpack name, up to 50 characters. Use Rename to apply it; an empty name restores the default.");
        explain(button("Rename", 274, 26, 74, () -> send("rename", 0, name.getValue())),
                "Apply the text field as this backpack's custom name. An empty field removes the custom name.");
        toggle("keep_search", "Keep search", true, 12, 52,
                "Remember this backpack's last item-search query and restore it the next time the backpack opens.");
        toggle("keep_tab", "Keep tab", true, 184, 52,
                "Remember the selected upgrade tab and reopen that tab with this backpack.");
        toggle("memory_components", "Exact memory", false, 12, 73,
                "Require remembered slots to match item components too, such as enchantments, potion contents, custom data, and damage.");
        toggle("shift_into_tab", "Shift into tab", false, 184, 73,
                "Try the selected upgrade's valid input slots before main storage when you Shift-click an item from your inventory.");
        toggle("share_access", "Share worn bag", false, 12, 94,
                "Allow nearby players with line of sight to open this backpack while you wear it, when the server also allows shared worn backpacks.");
        explain(button("Use my defaults", 184, 94, 164, () -> send("defaults_use", 0, "")),
                "Remove this backpack's personal-preference overrides so your saved player defaults take effect.");
        displaySlot = explain(field("Displayed storage slot", 12, 127, 46, Integer.toString(menu.bag().settings().getIntOr("display_slot", -1) + 1), 3),
                "Enter the storage slot whose item should appear on the backpack exterior. Slot 1 is the first slot; 0 disables the display.");
        explain(button("Display slot", 62, 127, 77, () -> {
            try { send("display_slot", Integer.parseInt(displaySlot.getValue()) - 1, ""); }
            catch (NumberFormatException ignored) { displaySlot.setValue("0"); }
        }), "Apply the entered exterior display slot. Use 0 to hide the exterior item.");
        explain(button("Rotate45°", 143, 127, 79, () -> send("display_rotation", 0, "")),
                "Rotate the exterior item clockwise by 45 degrees. Eight clicks complete a full turn.");
        explain(button("Depth -", 226, 127, 58, () -> send("display_depth", -1, "")),
                "Pull the exterior item outward from the front pocket by 1/16 pixel. Range -16 to 16 (one pixel total in either direction).");
        explain(button("Depth +", 288, 127, 60, () -> send("display_depth", 1, "")),
                "Push the exterior item deeper into the front pocket by 1/16 pixel. Range -16 to 16 (one pixel total in either direction).");
        templateName = explain(field("Settings template name", 12, 161, 270, savedTemplateName, 80),
                "Name a personal settings-only template, or enter namespace:name to preview or load a server data-pack template. Items and resources are never copied.");
        templateName.setResponder(value -> savedTemplateName = value);
        explain(button("<", 286, 161, 29, () -> cycleTemplate(-1)), "Select the previous saved personal settings template.");
        explain(button(">", 319, 161, 29, () -> cycleTemplate(1)), "Select the next saved personal settings template.");
        explain(button("Save", 12, 184, 58, () -> send("template_save", 0, templateName.getValue())),
                "Save this backpack's settings and upgrade configuration as a private personal template. Items, fluids, energy, experience, and captured mobs are excluded.");
        explain(button("Preview", 74, 184, 65, () -> send("template_preview", 0, templateName.getValue())),
                "Summarize the named template below without applying it to this backpack.");
        explain(button("Load", 143, 184, 58, () -> send("template_load", 0, templateName.getValue())),
                "Apply the named personal or data-pack settings template to this backpack. Stored items and resources remain untouched.");
        explain(button("Delete", 205, 184, 58, () -> send("template_delete", 0, templateName.getValue())),
                "Delete the named personal template. Server data-pack templates cannot be deleted here.");
        explain(button("Export pack", 267, 184, 81, () -> send("template_export", 0, templateName.getValue())),
                "Operator-only: export these settings as a server data pack, ready to enable with /datapack and /reload.");
        explain(button("Save as my defaults", 12, 211, 164, () -> send("defaults_save", 0, "")),
                "Save this backpack's preference toggles as your player defaults for backpacks without their own overrides.");
        explain(button("Back to backpack", 184, 211, 164, this::onClose),
                "Return to the backpack. Toggle changes apply immediately; text fields apply through their adjacent action buttons.");
        tooltips.refresh(minecraft);
    }
    private EditBox field(String label, int x, int y, int width, String initial, int max) {
        EditBox field = addRenderableWidget(new EditBox(font, left + x, top + y, width, 18, Component.literal(label)));
        field.setMaxLength(max); field.setValue(initial); return field;
    }
    private Button button(String text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), ignored -> action.run()).bounds(left + x, top + y, width, 18).build());
    }
    private <T extends net.minecraft.client.gui.components.AbstractWidget> T explain(T widget, String help) {
        return tooltips.add(widget, help);
    }
    private void toggle(String setting, String label, boolean initial, int x, int y, String help) {
        Button button = button(label, x, y, 164, () -> send("setting", 0, setting));
        toggles.add(new Toggle(setting, label, initial, button));
        explain(button, help);
    }
    private void send(String action, int value, String text) { ClientPlayNetworking.send(new MenuAction(menu.containerId, action, 0, value, text)); }
    private void cycleTemplate(int offset) {
        var names = menu.bag().settings().getListOrEmpty("template_names");
        if (names.isEmpty()) return;
        List<String> values = new ArrayList<>();
        for (int index = 0; index < names.size(); index++) names.getString(index).ifPresent(values::add);
        if (values.isEmpty()) return;
        int current = values.indexOf(templateName.getValue());
        templateName.setValue(values.get(Math.floorMod(current + offset, values.size())));
    }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.player.containerMenu != menu || !minecraft.player.isAlive()) { minecraft.gui.setScreen(null); return; }
        var settings = menu.bag().settings();
        for (Toggle toggle : toggles) toggle.button().setMessage(Component.literal(toggle.label() + ": " + (settings.getBooleanOr(toggle.setting(), toggle.initial()) ? "On" : "Off")));
        tooltips.refresh(minecraft);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(left, top, left + 360, top + 244, 0xffccb996);
        graphics.outline(left, top, 360, 244, 0xff493326);
        graphics.text(font, title, left + 12, top + 9, 0xff302a21, false);
        graphics.text(font, "Exterior item (slot0 disables). Rotation " + menu.bag().settings().getIntOr("display_rotation", 0)
                + "°; depth " + menu.bag().settings().getIntOr("display_depth", 0), left + 12, top + 115, 0xff302a21, false);
        graphics.text(font, "Settings templates — no items or resources are copied", left + 12, top + 150, 0xff302a21, false);
        graphics.text(font, font.plainSubstrByWidth(menu.bag().settings().getStringOr("template_preview",
                "Named saves; data packs use namespace:name"), 336), left + 12, top + 234, 0xff493326, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
    @Override public void onClose() { minecraft.gui.setScreen(minecraft.player != null && minecraft.player.containerMenu == menu ? previous : null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}
