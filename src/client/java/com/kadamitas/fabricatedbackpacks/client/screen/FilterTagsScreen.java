package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;

/** Explicit registry tag identifiers complement ghost item filtering. */
public final class FilterTagsScreen extends Screen {
    private final BackpackScreen parent;
    private final boolean cookingInput;
    private EditBox tag;
    private final ShiftTooltips tooltips = new ShiftTooltips();
    private int left, top, page;
    private String fingerprint = "";
    private String input = "";
    public FilterTagsScreen(BackpackScreen parent, boolean cookingInput) { super(Component.literal("Filter tags")); this.parent = parent; this.cookingInput = cookingInput; }
    @Override protected void init() {
        left = (width - 330) / 2;
        top = Math.max(4, (height - 236) / 2);
        tooltips.clear();
        tag = tooltips.add(addRenderableWidget(new EditBox(font, left + 10, top + 28, 218, 18, Component.literal("Tag identifier"))),
                "Enter a namespaced item tag such as minecraft:logs. The upgrade matches items belonging to selected tags.");
        tag.setMaxLength(156); tag.setValue(input); tag.setHint(Component.literal("minecraft:logs")); tag.setResponder(value -> input = value);
        tooltips.add(button("Add / remove", 232, 28, 88, () -> toggle(input)),
                "Toggle the entered tag in this upgrade's selected-tag list. Invalid or unnamespaced identifiers are rejected.");
        List<String> selected = selected();
        int pages = Math.max(1, Math.ceilDiv(selected.size(), 7));
        page = Math.clamp(page, 0, pages - 1);
        for (int row = 0; row < 7 && page * 7 + row < selected.size(); row++) {
            String id = selected.get(page * 7 + row);
            tooltips.add(button(id, 10, 60 + row * 20, 310, () -> toggle(id)),
                    "Remove the selected tag " + id + " from this upgrade's filter.");
        }
        tooltips.add(button("<", 10, 207, 28, () -> { page = Math.floorMod(page - 1, pages); rebuildWidgets(); }), "Show the previous page of selected tags.");
        tooltips.add(button(">", 42, 207, 28, () -> { page = (page + 1) % pages; rebuildWidgets(); }), "Show the next page of selected tags.");
        tooltips.add(button("Back", 240, 207, 80, this::onClose), "Return to the selected upgrade tab. Tag changes apply immediately.");
        fingerprint = selected.toString();
        tooltips.refresh(minecraft);
    }
    private Button button(String text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), ignored -> action.run()).bounds(left + x, top + y, width, 18).build());
    }
    private List<String> selected() {
        return parent.getMenu().selected().map(upgrade -> Arrays.stream(parent.getMenu().bag().settings(upgrade).getStringOr(cookingInput ? "input_tags" : "tags", "").split(","))
                .filter(value -> !value.isBlank()).sorted().toList()).orElse(List.of());
    }
    private void toggle(String value) {
        ClientPlayNetworking.send(new MenuAction(parent.getMenu().containerId, "upgrade", 0, 0, (cookingInput ? "input_tag:" : "tag:") + value.strip()));
    }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.player.containerMenu != parent.getMenu()) { minecraft.gui.setScreen(null); return; }
        if (!fingerprint.equals(selected().toString())) rebuildWidgets();
        else tooltips.refresh(minecraft);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(left, top, left + 330, top + 236, 0xffccb996);
        graphics.outline(left, top, 330, 236, 0xff493326);
        graphics.text(font, title, left + 10, top + 9, 0xff302a21, false);
        graphics.text(font, "Click a selected tag to remove it; use Match tags in the tab.", left + 10, top + 50, 0xff493326, false);
        graphics.text(font, (page + 1) + "/" + Math.max(1, Math.ceilDiv(selected().size(), 7)), left + 78, top + 212, 0xff493326, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
    @Override public void onClose() { minecraft.gui.setScreen(minecraft.player != null && minecraft.player.containerMenu == parent.getMenu() ? parent : null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}
