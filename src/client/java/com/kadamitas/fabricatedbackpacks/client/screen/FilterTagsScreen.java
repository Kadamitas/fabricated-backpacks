package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
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
    private int left, top, page;
    private String fingerprint = "";
    private String input = "";
    public FilterTagsScreen(BackpackScreen parent, boolean cookingInput) { super(Component.literal("Filter tags")); this.parent = parent; this.cookingInput = cookingInput; }
    @Override protected void init() {
        left = (width - 330) / 2;
        top = Math.max(4, (height - 236) / 2);
        tag = addRenderableWidget(new EditBox(font, left + 10, top + 28, 218, 18, Component.literal("Tag identifier")));
        tag.setMaxLength(156); tag.setValue(input); tag.setHint(Component.literal("minecraft:logs")); tag.setResponder(value -> input = value);
        button("Add / remove", 232, 28, 88, () -> toggle(input));
        List<String> selected = selected();
        int pages = Math.max(1, Math.ceilDiv(selected.size(), 7));
        page = Math.clamp(page, 0, pages - 1);
        for (int row = 0; row < 7 && page * 7 + row < selected.size(); row++) {
            String id = selected.get(page * 7 + row);
            button(id, 10, 60 + row * 20, 310, () -> toggle(id));
        }
        button("<", 10, 207, 28, () -> { page = Math.floorMod(page - 1, pages); rebuildWidgets(); });
        button(">", 42, 207, 28, () -> { page = (page + 1) % pages; rebuildWidgets(); });
        button("Back", 240, 207, 80, this::onClose);
        fingerprint = selected.toString();
    }
    private void button(String text, int x, int y, int width, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(text), ignored -> action.run()).bounds(left + x, top + y, width, 18).build());
    }
    private List<String> selected() {
        return parent.getMenu().selected().map(upgrade -> Arrays.stream(NbtAccess.getStringOr(parent.getMenu().bag().settings(upgrade), cookingInput ? "input_tags" : "tags", "").split(","))
                .filter(value -> !value.isBlank()).sorted().toList()).orElse(List.of());
    }
    private void toggle(String value) {
        ClientPlayNetworking.send(new MenuAction(parent.getMenu().containerId, "upgrade", 0, 0, (cookingInput ? "input_tag:" : "tag:") + value.strip()));
    }
    @Override public void tick() {
        if (minecraft.player == null || minecraft.player.containerMenu != parent.getMenu()) { minecraft.setScreen(null); return; }
        if (!fingerprint.equals(selected().toString())) rebuildWidgets();
    }
    // This screen paints its own backdrop before its native widgets.
    @Override public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(left, top, left + 330, top + 236, 0xffccb996);
        graphics.renderOutline(left, top, 330, 236, 0xff493326);
        graphics.drawString(font, title, left + 10, top + 9, 0xff302a21, false);
        graphics.drawString(font, "Click a selected tag to remove it; use Match tags in the tab.", left + 10, top + 50, 0xff493326, false);
        graphics.drawString(font, (page + 1) + "/" + Math.max(1, Math.ceilDiv(selected().size(), 7)), left + 78, top + 212, 0xff493326, false);
        super.render(graphics, mouseX, mouseY, delta);
    }
    @Override public void onClose() { minecraft.setScreen(minecraft.player != null && minecraft.player.containerMenu == parent.getMenu() ? parent : null); }
    @Override public boolean isPauseScreen() { return false; }
}
