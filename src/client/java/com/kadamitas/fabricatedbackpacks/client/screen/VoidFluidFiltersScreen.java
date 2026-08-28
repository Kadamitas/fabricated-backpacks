package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Fluid ghosts copy a server-observed cursor container without consuming it or its contents. */
public final class VoidFluidFiltersScreen extends Screen {
    private record Row(int index, Button button) {}
    private final BackpackScreen parent;
    private final List<Row> rows = new ArrayList<>();
    private int left, top, page;

    VoidFluidFiltersScreen(BackpackScreen parent) { super(Component.literal("Fluid filters")); this.parent = parent; }

    @Override protected void init() {
        left = (width - 304) / 2;
        top = Math.max(2, (height - 226) / 2);
        rows.clear();
        int count = parent.getMenu().selected().map(upgrade -> parent.getMenu().bag().filterSlots(upgrade)).orElse(0);
        int pages = Math.max(1, Math.ceilDiv(count, 7));
        page = Math.clamp(page, 0, pages - 1);
        for (int index = page * 7; index < Math.min(count, (page + 1) * 7); index++) {
            final int row = index;
            Button button = addRenderableWidget(Button.builder(Component.empty(), ignored -> ClientPlayNetworking.send(
                    new MenuAction(parent.getMenu().containerId, "upgrade", 0, 0, "fluid_filter:" + row)))
                    .bounds(left + 10, top + 55 + (index % 7) * 20, 284, 18).build());
            rows.add(new Row(index, button));
        }
        addRenderableWidget(Button.builder(Component.literal("<"), ignored -> { page = Math.floorMod(page - 1, pages); rebuildWidgets(); })
                .bounds(left + 10, top + 201, 28, 16).build());
        addRenderableWidget(Button.builder(Component.literal(">"), ignored -> { page = (page + 1) % pages; rebuildWidgets(); })
                .bounds(left + 42, top + 201, 28, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> onClose()).bounds(left + 234, top + 201, 60, 16).build());
        refresh();
    }

    private void refresh() {
        parent.getMenu().selected().ifPresent(upgrade -> rows.forEach(row -> {
            Component description = ResourceRuntime.fluidFilterDescription(parent.getMenu().bag(), upgrade.slot(), row.index());
            row.button().setMessage(Component.literal(font.plainSubstrByWidth(description.getString(), 276)));
            row.button().setTooltip(Tooltip.create(description));
        }));
    }

    @Override public void tick() {
        if (!valid()) { minecraft.gui.setScreen(null); return; }
        refresh();
    }
    private boolean valid() { return minecraft.player != null && minecraft.player.isAlive() && minecraft.player.containerMenu == parent.getMenu(); }
    @Override public void onClose() { minecraft.gui.setScreen(valid() ? parent : null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xc0111c24);
        graphics.fill(left, top, left + 304, top + 226, 0xffccb996);
        graphics.outline(left, top, 304, 226, 0xff493326);
        graphics.text(font, title, left + 10, top + 9, 0xff302a21, false);
        graphics.textWithWordWrap(font, Component.literal("Click a row with a filled container on the cursor. Empty cursor clears the row."), left + 10, top + 26, 282, 0xff493326);
        graphics.text(font, "Page " + (page + 1), left + 82, top + 205, 0xff493326, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }
}
