package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.upgrade.AlchemyRuntime;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** Per-filter conditions are edited through the same authorized menu as the ghost filters. */
public final class SlotRulesScreen extends Screen {
    private static final int PAGE_SIZE = 6;
    private final BackpackScreen previous;
    private final BackpackMenu menu;
    private final int upgradeSlot;
    private final com.kadamitas.fabricatedbackpacks.domain.UpgradeKind upgradeKind;
    private final boolean alchemy;
    private final List<Row> rows = new ArrayList<>();
    private int left, top, page;
    private record Row(int slot, Button condition, Button lower, Button higher) {}

    SlotRulesScreen(BackpackScreen previous, InstalledUpgrade upgrade) {
        super(Component.literal(upgrade.kind().family().equals("alchemy") ? "Potion conditions" : "Refill targets"));
        this.previous = previous;
        this.menu = previous.getMenu();
        upgradeSlot = upgrade.slot();
        upgradeKind = upgrade.kind();
        alchemy = upgrade.kind().family().equals("alchemy");
    }

    @Override protected void init() {
        left = (width - 312) / 2;
        top = Math.max(2, (height - 228) / 2);
        rows.clear();
        var upgrade = menu.selected().orElse(null);
        if (upgrade == null) return;
        int count = menu.bag().filterSlots(upgrade);
        int pages = Math.max(1, Math.ceilDiv(count, PAGE_SIZE));
        page = Math.clamp(page, 0, pages - 1);
        for (int slot = page * PAGE_SIZE; slot < Math.min(count, (page + 1) * PAGE_SIZE); slot++) {
            final int selected = slot;
            int y = 36 + (slot % PAGE_SIZE) * 24;
            Button condition = button("", 52, y, alchemy ? 136 : 248,
                    () -> send((alchemy ? "alchemy_condition:" : "refill_target:") + selected));
            Button lower = alchemy ? button("−", 246, y, 24, () -> send("alchemy_health:" + selected + ":-5")) : null;
            Button higher = alchemy ? button("+", 276, y, 24, () -> send("alchemy_health:" + selected + ":5")) : null;
            rows.add(new Row(slot, condition, lower, higher));
        }
        button("<", 12, 188, 34, () -> { page = Math.floorMod(page - 1, pages); rebuildWidgets(); });
        button(">", 266, 188, 34, () -> { page = (page + 1) % pages; rebuildWidgets(); });
        button("Back", 52, 188, 204, this::onClose);
        refresh(upgrade);
    }

    private Button button(String text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), ignored -> action.run())
                .bounds(left + x, top + y, width, 18).build());
    }

    private void send(String action) { ClientPlayNetworking.send(new MenuAction(menu.containerId, "upgrade", 0, 0, action)); }

    private String condition(InstalledUpgrade upgrade, int row) {
        return menu.bag().settings(upgrade).getStringOr((alchemy ? "alchemy_condition_" : "refill_target_") + row,
                alchemy ? AlchemyRuntime.defaultCondition(menu.bag().ghost(upgrade, row)).name() : "ANY");
    }

    private void refresh(InstalledUpgrade upgrade) {
        for (var row : rows) {
            String selected = condition(upgrade, row.slot());
            String label = (row.slot() + 1) + ": " + selected.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
            row.condition().setMessage(Component.literal(font.plainSubstrByWidth(label, row.condition().getWidth() - 8)));
            row.condition().setTooltip(Tooltip.create(Component.literal(label)));
            row.condition().active = !menu.bag().ghost(upgrade, row.slot()).isEmpty();
            if (alchemy) {
                boolean hurt = row.condition().active && selected.equals("HURT");
                int health = menu.bag().settings(upgrade).getIntOr("alchemy_health_" + row.slot(), 75);
                row.lower().active = hurt && health > 0;
                row.higher().active = hurt && health < 100;
                row.lower().setTooltip(Tooltip.create(Component.literal("Use below " + health + "% health; lower by 5 percentage points")));
                row.higher().setTooltip(Tooltip.create(Component.literal("Use below " + health + "% health; raise by 5 percentage points")));
            }
        }
    }

    @Override public void tick() {
        var upgrade = menu.selected().orElse(null);
        if (!sessionCurrent()) minecraft.gui.setScreen(null);
        else refresh(upgrade);
    }

    private boolean sessionCurrent() {
        return minecraft.player != null && minecraft.player.isAlive() && minecraft.player.containerMenu == menu
                && menu.selected().filter(upgrade -> upgrade.slot() == upgradeSlot && upgrade.kind() == upgradeKind).isPresent();
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(left, top, left + 312, top + 228, 0xffccb996);
        graphics.outline(left, top, 312, 228, 0xff493326);
        graphics.text(font, title, left + 12, top + 10, 0xff302a21, false);
        graphics.text(font, "Set items in the upgrade's ghost filters first", left + 12, top + 23, 0xff493326, false);
        menu.selected().ifPresent(upgrade -> {
            for (var row : rows) {
                int y = top + 37 + row.slot() % PAGE_SIZE * 24;
                graphics.text(font, Integer.toString(row.slot() + 1), left + 12, y + 4, 0xff493326, false);
                graphics.fakeItem(menu.bag().ghost(upgrade, row.slot()), left + 29, y);
                if (alchemy && condition(upgrade, row.slot()).equals("HURT"))
                    graphics.text(font, "< " + menu.bag().settings(upgrade).getIntOr("alchemy_health_" + row.slot(), 75) + "%",
                            left + 193, y + 4, 0xff493326, false);
            }
            graphics.centeredText(font, "Page " + (page + 1) + "/" + Math.max(1, Math.ceilDiv(menu.bag().filterSlots(upgrade), PAGE_SIZE)),
                    left + 156, top + 215, 0xff493326);
        });
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override public void onClose() { minecraft.gui.setScreen(sessionCurrent() ? previous : null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }
}
