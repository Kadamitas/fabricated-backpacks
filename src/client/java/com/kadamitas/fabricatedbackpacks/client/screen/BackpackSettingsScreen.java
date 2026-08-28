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
        button("Slot tools", 274, 5, 74, () -> minecraft.gui.setScreen(new StorageToolsScreen(this, menu)));
        name = field("Backpack name", 12, 26, 258, menu.bag().stack().getHoverName().getString(), 50);
        button("Rename", 274, 26, 74, () -> send("rename", 0, name.getValue()));
        toggle("keep_search", "Keep search", true, 12, 52);
        toggle("keep_tab", "Keep tab", true, 184, 52);
        toggle("memory_components", "Exact memory", false, 12, 73);
        toggle("shift_into_tab", "Shift into tab", false, 184, 73);
        toggle("share_access", "Share worn bag", false, 12, 94);
        button("Use my defaults", 184, 94, 164, () -> send("defaults_use", 0, ""));
        displaySlot = field("Displayed storage slot", 12, 127, 46, Integer.toString(menu.bag().settings().getIntOr("display_slot", -1) + 1), 3);
        button("Display slot", 62, 127, 77, () -> {
            try { send("display_slot", Integer.parseInt(displaySlot.getValue()) - 1, ""); }
            catch (NumberFormatException ignored) { displaySlot.setValue("0"); }
        });
        button("Rotate45°", 143, 127, 79, () -> send("display_rotation", 0, ""));
        button("Depth -", 226, 127, 58, () -> send("display_depth", -1, ""));
        button("Depth +", 288, 127, 60, () -> send("display_depth", 1, ""));
        templateName = field("Settings template name", 12, 161, 270, savedTemplateName, 80);
        templateName.setResponder(value -> savedTemplateName = value);
        button("<", 286, 161, 29, () -> cycleTemplate(-1));
        button(">", 319, 161, 29, () -> cycleTemplate(1));
        button("Save", 12, 184, 58, () -> send("template_save", 0, templateName.getValue()));
        button("Preview", 74, 184, 65, () -> send("template_preview", 0, templateName.getValue()));
        button("Load", 143, 184, 58, () -> send("template_load", 0, templateName.getValue()));
        button("Delete", 205, 184, 58, () -> send("template_delete", 0, templateName.getValue()));
        button("Export pack", 267, 184, 81, () -> send("template_export", 0, templateName.getValue()));
        button("Save as my defaults", 12, 211, 164, () -> send("defaults_save", 0, ""));
        button("Back to backpack", 184, 211, 164, this::onClose);
    }
    private EditBox field(String label, int x, int y, int width, String initial, int max) {
        EditBox field = addRenderableWidget(new EditBox(font, left + x, top + y, width, 18, Component.literal(label)));
        field.setMaxLength(max); field.setValue(initial); return field;
    }
    private Button button(String text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), ignored -> action.run()).bounds(left + x, top + y, width, 18).build());
    }
    private void toggle(String setting, String label, boolean initial, int x, int y) {
        Button button = button(label, x, y, 164, () -> send("setting", 0, setting));
        toggles.add(new Toggle(setting, label, initial, button));
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
