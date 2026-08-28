package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

public final class BackpackScreen extends AbstractContainerScreen<BackpackMenu> {
    private EditBox search;
    private Button storagePage;
    private StringWidget upgradeHeading;
    private StringWidget noResults;
    private String query = "";
    private int controlPage;
    private int ghostPage;
    private int auxiliaryPage;
    private String selectedKey = "";
    private final List<OptionButton> optionButtons = new ArrayList<>();
    private final Map<Integer, net.minecraft.world.entity.LivingEntity> capturedPreviews = new HashMap<>();
    private String captureFingerprint = "";
    private boolean customClick;
    private boolean searchInitialized;
    private String sentQuery = "";
    private int searchDebounce;
    private String sentMask = "";
    private com.kadamitas.fabricatedbackpacks.browser.BrowserQuery parsedQuery = com.kadamitas.fabricatedbackpacks.browser.BrowserQuery.parse("");
    private record OptionButton(String action, Button button) {}

    public BackpackScreen(BackpackMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, menu.imageWidth(), menu.imageHeight());
    }
    @Override protected void init() {
        super.init();
        optionButtons.clear();
        storagePage = null;
        upgradeHeading = null;
        heading(title, 8, 5, menu.storageWidth() - 16, 0x382b20);
        noResults = addRenderableWidget(new StringWidget(leftPos + 12, topPos + 38, menu.storageWidth() - 24, font.lineHeight,
                Component.literal("No matching items").withStyle(style -> style.withColor(0x493326)), font)
                .setMaxWidth(menu.storageWidth() - 24));
        if (!searchInitialized) {
            query = menu.preferences().getBooleanOr("keep_search", true) ? menu.bag().settings().getStringOr("last_search", "") : "";
            searchInitialized = true;
            sentQuery = query;
        }
        search = addRenderableWidget(new EditBox(font, leftPos + 8, topPos + 17, menu.storageWidth() - 16, 12, Component.translatable("screen.fabricated_backpacks.search")));
        search.setMaxLength(120);
        search.setValue(query);
        search.setHint(Component.translatable("screen.fabricated_backpacks.search_hint"));
        parsedQuery = com.kadamitas.fabricatedbackpacks.browser.BrowserQuery.parse(query);
        search.setResponder(value -> { query = value; parsedQuery = com.kadamitas.fabricatedbackpacks.browser.BrowserQuery.parse(value); searchDebounce = 5; refreshStorageView(); });
        int bar = 34 + Math.min(BackpackMenu.VISIBLE_ROWS, menu.bag().rows()) * 18;
        button("Sort", 8, bar, 34, () -> menuButton(0)).setTooltip(Tooltip.create(Component.literal("Sort by name. Right click a stack normally; use Memory to reserve slots.")));
        button("Count", 44, bar, 37, () -> menuButton(3));
        button("Mod", 83, bar, 30, () -> menuButton(4));
        button("Mode", 115, bar, 36, () -> menuButton(2));
        if (menu.bag().rows() > BackpackMenu.VISIBLE_ROWS) storagePage = button(">", menu.storageWidth() - 22, bar, 14, () -> menuButton(1));
        button("Items", menu.storageWidth() + 49, 17, 40, this::openBrowser);
        button("Gear", menu.storageWidth() + 92, 17, 39, () -> sendGlobal("equipment"));
        button("Prefs", menu.storageWidth() + 8, 218, 35, () -> minecraft.gui.setScreen(new BackpackSettingsScreen(this)));
        for (int slot = 0; slot < menu.bag().upgrades().getContainerSize(); slot++) {
            final int selected = slot;
            button(Integer.toString(slot + 1), menu.storageWidth() + 28, 33 + slot * 18, 15, () -> { controlPage = 0; menuButton(100 + selected); });
        }
        menu.selected().ifPresent(upgrade -> {
            int x = menu.storageWidth() + 50;
            upgradeHeading = heading(upgrade.stack().getHoverName(), x, 37, imageWidth - x - 8, 0x302a21);
            layoutAuxiliary(upgrade);
            addControls(upgrade);
        });
        selectedKey = selectionKey();
        refreshStorageView();
    }
    private StringWidget heading(Component text, int x, int y, int width, int color) {
        var label = new StringWidget(leftPos + x, topPos + y, width, font.lineHeight,
                text.copy().withStyle(style -> style.withColor(color)), font).setMaxWidth(width);
        label.setTooltip(Tooltip.create(text));
        return addRenderableWidget(label);
    }
    private void addControls(InstalledUpgrade upgrade) {
        if (BackpackMenu.isWorkstation(upgrade.kind())) {
            button("Open station", menu.storageWidth() + 50, 58, 80, () -> send("workstation", 0, 0, ""));
            return;
        }
        int inventoryPages = Math.max(1, Math.ceilDiv(menu.bag().inventorySlots(upgrade), auxiliaryPageSize(upgrade)));
        if (inventoryPages > 1) button("Slots " + (auxiliaryPage + 1) + "/" + inventoryPages,
                menu.storageWidth() + 50, 56 + auxiliaryRows(upgrade) * 18, 84,
                () -> { auxiliaryPage = (auxiliaryPage + 1) % inventoryPages; rebuildWidgets(); });
        int filterPages = Math.max(1, Math.ceilDiv(menu.bag().filterSlots(upgrade), filterPageSize(upgrade)));
        if (filterPages > 1) button("Filters " + (ghostPage + 1) + "/" + filterPages,
                menu.storageWidth() + 50, ghostY(upgrade) + filterRows(upgrade) * 18 + 2, 84,
                () -> { ghostPage = (ghostPage + 1) % filterPages; rebuildWidgets(); });
        List<String> actions = UpgradeControls.actions(menu.bag(), upgrade);
        int y = controlY(upgrade);
        int visible = Math.max(1, (imageHeight - y - 24) / 17);
        int pageCount = Math.max(1, Math.ceilDiv(actions.size(), visible));
        controlPage = Math.min(controlPage, pageCount - 1);
        for (int i = 0; i < visible && controlPage * visible + i < actions.size(); i++) {
            String action = actions.get(controlPage * visible + i);
            Button button = button(UpgradeControls.label(action), menu.storageWidth() + 49, y + i * 17, 84,
                    () -> {
                        if (action.equals("tags") || action.equals("input_tags")) minecraft.gui.setScreen(new FilterTagsScreen(this, action.equals("input_tags")));
                        else if (action.equals("fluids")) minecraft.gui.setScreen(new VoidFluidFiltersScreen(this));
                        else if (action.equals("slot_rules")) minecraft.gui.setScreen(new SlotRulesScreen(this, upgrade));
                        else send(action.startsWith("inception_") ? "setting" : "upgrade", 0, 0, action);
                    });
            button.setTooltip(Tooltip.create(Component.literal(UpgradeControls.label(action))));
            optionButtons.add(new OptionButton(action, button));
        }
        if (pageCount > 1) button("More " + (controlPage + 1) + "/" + pageCount, menu.storageWidth() + 49, imageHeight - 20, 84,
                () -> { controlPage = (controlPage + 1) % pageCount; rebuildWidgets(); });
    }
    private int auxiliaryPageSize(InstalledUpgrade upgrade) { return menu.bag().inventoryColumns(upgrade) * 3; }
    private int filterPageSize(InstalledUpgrade upgrade) { return menu.bag().filterColumns(upgrade) * 3; }
    private int auxiliaryRows(InstalledUpgrade upgrade) {
        return Math.ceilDiv(Math.min(menu.bag().inventorySlots(upgrade), auxiliaryPageSize(upgrade)), menu.bag().inventoryColumns(upgrade));
    }
    private int filterRows(InstalledUpgrade upgrade) {
        return Math.ceilDiv(Math.min(menu.bag().filterSlots(upgrade), filterPageSize(upgrade)), menu.bag().filterColumns(upgrade));
    }
    private int ghostY(InstalledUpgrade upgrade) {
        return 54 + auxiliaryRows(upgrade) * 18 + (menu.bag().inventorySlots(upgrade) > 0 ? 4 : 0)
                + (menu.bag().inventorySlots(upgrade) > auxiliaryPageSize(upgrade) ? 16 : 0);
    }
    private int controlY(InstalledUpgrade upgrade) {
        return ghostY(upgrade) + filterRows(upgrade) * 18 + 8 + (menu.bag().filterSlots(upgrade) > filterPageSize(upgrade) ? 16 : 0);
    }
    private void layoutAuxiliary(InstalledUpgrade upgrade) {
        int pageSize = auxiliaryPageSize(upgrade);
        int pages = Math.max(1, Math.ceilDiv(menu.bag().inventorySlots(upgrade), pageSize));
        auxiliaryPage = Math.clamp(auxiliaryPage, 0, pages - 1);
        ghostPage = Math.clamp(ghostPage, 0, Math.max(0, Math.ceilDiv(menu.bag().filterSlots(upgrade), filterPageSize(upgrade)) - 1));
        menu.setAuxiliaryWindow(auxiliaryPage * pageSize, pageSize);
        int columns = menu.bag().inventoryColumns(upgrade);
        for (int index = 0; index < menu.auxiliaryCount(); index++) {
            var position = (com.kadamitas.fabricatedbackpacks.client.mixin.SlotPositionAccess) menu.slots.get(menu.auxiliaryStart() + index);
            position.fabricatedBackpacks$x(menu.storageWidth() + 51 + index % pageSize % columns * 18);
            position.fabricatedBackpacks$y(54 + index % pageSize / columns * 18);
        }
    }
    private Button button(String label, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), ignored -> action.run()).bounds(leftPos + x, topPos + y, width, 14).build());
    }
    private void menuButton(int id) { minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id); }
    private void send(String action, int index, int value, String text) { ClientPlayNetworking.send(new MenuAction(menu.containerId, action, index, value, text)); }
    private void sendGlobal(String action) { ClientPlayNetworking.send(new MenuAction(-1, action, 0, 0, "")); }
    private void openBrowser() { com.kadamitas.fabricatedbackpacks.client.browser.RecipeBrowserClient.open(this); }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xffccb996);
        graphics.outline(leftPos, topPos, imageWidth, imageHeight, 0xff493326);
        graphics.fill(leftPos + menu.storageWidth(), topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xffaf9874);
        for (Slot slot : menu.slots) if (slot.isActive()) slotBackground(graphics, slot.x, slot.y, 0xffae9978);
        for (int slot = 0; slot < menu.bag().getContainerSize(); slot++) {
            Slot display = menu.slots.get(slot);
            if (!display.isActive()) continue;
            if (java.util.Arrays.stream(menu.bag().settings().getIntArray("no_sort").orElseGet(() -> new int[0])).anyMatch(i -> i == display.getContainerSlot()))
                graphics.outline(leftPos + display.x - 1, topPos + display.y - 1, 18, 18,
                        0xff000000 | menu.bag().settings().getIntOr("no_sort_color", 0xdb8c39) & 0xffffff);
            menu.bag().stack().getOrDefault(BagComponents.MEMORY, com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot.EMPTY).entries().stream()
                    .filter(entry -> entry.slot() == display.getContainerSlot()).findFirst().ifPresent(entry -> {
                        graphics.outline(leftPos + display.x - 1, topPos + display.y - 1, 18, 18, 0xff568f9b);
                        if (display.getItem().isEmpty()) {
                            graphics.fakeItem(entry.create(), leftPos + display.x, topPos + display.y);
                            graphics.fill(leftPos + display.x, topPos + display.y, leftPos + display.x + 16, topPos + display.y + 16, 0x88ae9978);
                        }
                    });
        }
        if (!menu.filtering()) {
            drawResources(graphics, mouseX, mouseY);
            drawCaptures(graphics, mouseX, mouseY);
        }
        menu.selected().ifPresent(upgrade -> drawUpgrade(graphics, upgrade, mouseX, mouseY));
    }
    private List<InstalledUpgrade> resources() {
        return menu.bag().installedUpgrades().stream()
                .filter(upgrade -> upgrade.kind() == UpgradeKind.TANK || upgrade.kind() == UpgradeKind.BATTERY).toList();
    }
    private void drawResources(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<InstalledUpgrade> resources = resources();
        int firstColumn = menu.bag().columns() - resources.size() * 2;
        int height = Math.min(BackpackMenu.VISIBLE_ROWS, menu.bag().rows()) * 18 - 2;
        for (int index = 0; index < resources.size(); index++) {
            InstalledUpgrade upgrade = resources.get(index);
            int x = 8 + (firstColumn + index * 2) * 18;
            boolean tank = upgrade.kind() == UpgradeKind.TANK;
            long amount = tank ? ResourceRuntime.tankStoredMb(menu.bag(), upgrade.slot()) : ResourceRuntime.batteryStored(menu.bag(), upgrade.slot());
            long capacity = tank ? ResourceRuntime.tankCapacityMb(menu.bag(), upgrade.slot()) : ResourceRuntime.batteryCapacity(menu.bag(), upgrade.slot());
            var fluid = tank ? ResourceRuntime.tankFluid(menu.bag(), upgrade.slot()) : null;
            boolean experience = tank && !fluid.isBlank() && fluid.getFluid() == com.kadamitas.fabricatedbackpacks.resource.ResourceComponents.EXPERIENCE;
            int color = experience ? 0xff87d64a : tank && !fluid.isBlank() ? FluidVariantRendering.getColor(fluid) | 0xff000000 : 0xffeeb94e;
            graphics.fill(leftPos + x - 1, topPos + 31, leftPos + x + 35, topPos + 33 + height, 0xff594a38);
            graphics.fill(leftPos + x, topPos + 32, leftPos + x + 34, topPos + 32 + height, 0xff403e38);
            int fill = amount == 0 ? 0 : (int) Math.clamp((double) amount / Math.max(1, capacity) * height, 1, height);
            if (fill > 0) graphics.fill(leftPos + x + 1, topPos + 32 + height - fill, leftPos + x + 33, topPos + 32 + height, color);
            graphics.text(font, tank ? "Tank" : "Power", leftPos + x + 2, topPos + 35, 0xffffffff, true);
            graphics.text(font, shortAmount(amount), leftPos + x + 2, topPos + height + 19, 0xffffffff, true);
            if (isHovering(x, 32, 34, height, mouseX, mouseY)) {
                String name = experience ? "Liquid experience" : tank ? fluid.isBlank() ? "Empty tank" : FluidVariantRendering.getTooltip(fluid).stream()
                        .findFirst().map(Component::getString).orElse("Fluid") : "Stored energy";
                graphics.setTooltipForNextFrame(font, Component.literal(name + ": " + amount + " / " + capacity
                        + (tank ? " mB" : " E") + " — click with a container to transfer"), mouseX, mouseY);
            }
        }
    }
    private static String shortAmount(long amount) {
        if (amount >= 1_000_000_000) return String.format(Locale.ROOT, "%.1fG", amount / 1_000_000_000.0);
        if (amount >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", amount / 1_000_000.0);
        if (amount >= 10_000) return String.format(Locale.ROOT, "%.0fk", amount / 1000.0);
        return Long.toString(amount);
    }
    private void drawCaptures(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        var captures = menu.bag().settings().getListOrEmpty("captured_entities");
        String fingerprint = captures.toString();
        if (!fingerprint.equals(captureFingerprint)) {
            capturedPreviews.clear();
            captureFingerprint = fingerprint;
        }
        for (int index = 0; index < captures.size(); index++) {
            var capture = captures.getCompoundOrEmpty(index);
            int row = capture.getIntOr("y", 0) - menu.page() * BackpackMenu.VISIBLE_ROWS;
            int rows = capture.getIntOr("height", 1);
            if (row >= BackpackMenu.VISIBLE_ROWS || row + rows <= 0) continue;
            int x = 8 + capture.getIntOr("x", 0) * 18;
            int y = 32 + Math.max(0, row) * 18;
            int width = capture.getIntOr("width", 1) * 18 - 2;
            int height = (Math.min(BackpackMenu.VISIBLE_ROWS, row + rows) - Math.max(0, row)) * 18 - 2;
            graphics.fill(leftPos + x, topPos + y, leftPos + x + width, topPos + y + height, 0xff7b8873);
            graphics.outline(leftPos + x, topPos + y, width, height, 0xff344c39);
            if (minecraft.level != null) {
                var preview = capturedPreviews.get(index);
                if (preview == null) {
                    var loaded = net.minecraft.world.entity.EntityType.loadEntityRecursive(capture.getCompoundOrEmpty("entity"), minecraft.level,
                            new net.minecraft.world.entity.EntitySpawnRequest(net.minecraft.world.entity.EntitySpawnReason.LOAD, false),
                            net.minecraft.world.entity.EntityProcessor.NOP);
                    if (loaded instanceof net.minecraft.world.entity.LivingEntity living) { preview = living; capturedPreviews.put(index, living); }
                }
                if (preview != null) net.minecraft.client.gui.screens.inventory.InventoryScreen.extractEntityInInventoryFollowsMouse(graphics,
                        leftPos + x + 2, topPos + y + 2, leftPos + x + width - 2, topPos + y + height - 2,
                        Math.max(8, Math.min(35, (int) (height / Math.max(1.0, preview.getBbHeight())))), 0.1f, mouseX, mouseY, preview);
            }
            if (isHovering(x, y, width, height, mouseX, mouseY)) graphics.setTooltipForNextFrame(font,
                    Component.literal(capture.getStringOr("name", "Captured mob") + " — right click to release ahead of you"), mouseX, mouseY);
        }
    }
    private void slotBackground(GuiGraphicsExtractor graphics, int x, int y, int color) {
        graphics.fill(leftPos + x - 1, topPos + y - 1, leftPos + x + 17, topPos + y + 17, 0xff594a38);
        graphics.fill(leftPos + x, topPos + y, leftPos + x + 16, topPos + y + 16, color);
    }
    private void drawUpgrade(GuiGraphicsExtractor graphics, InstalledUpgrade upgrade, int mouseX, int mouseY) {
        int x = menu.storageWidth() + 51;
        var settings = menu.bag().settings(upgrade);
        int firstFilter = ghostPage * filterPageSize(upgrade);
        for (int index = firstFilter; index < Math.min(menu.bag().filterSlots(upgrade), firstFilter + filterPageSize(upgrade)); index++) {
            int local = index - firstFilter;
            int gx = x + local % menu.bag().filterColumns(upgrade) * 18;
            int gy = ghostY(upgrade) + local / menu.bag().filterColumns(upgrade) * 18;
            slotBackground(graphics, gx, gy, 0xff797d67);
            ItemStack ghost = menu.bag().ghost(upgrade, index);
            if (!ghost.isEmpty()) graphics.fakeItem(ghost, leftPos + gx, topPos + gy);
            if (isHovering(gx, gy, 16, 16, mouseX, mouseY)) graphics.setTooltipForNextFrame(font,
                    ghost.isEmpty() ? Component.translatable("screen.fabricated_backpacks.ghost_help") : ghost.getHoverName(), mouseX, mouseY);
        }
        if (upgrade.kind().family().equals("jukebox") && settings.getBooleanOr("playing", false)) {
            int active = settings.getIntOr("active_slot", -1);
            int first = auxiliaryPage * auxiliaryPageSize(upgrade);
            if (active >= first && active < first + auxiliaryPageSize(upgrade)) {
                int local = active - first;
                graphics.outline(leftPos + x + local % menu.bag().inventoryColumns(upgrade) * 18 - 1,
                        topPos + 54 + local / menu.bag().inventoryColumns(upgrade) * 18 - 1, 18, 18, 0xff54db80);
            }
            long start = settings.getLongOr("song_started", 0), end = settings.getLongOr("song_finish", 1);
            long now = minecraft.level == null ? start : minecraft.level.getGameTime();
            int length = (int) Math.clamp(72 * (now - start) / Math.max(1, end - start), 0, 72);
            graphics.fill(leftPos + x, topPos + controlY(upgrade) - 5, leftPos + x + length, topPos + controlY(upgrade) - 3, 0xff4d8f65);
        }
        if (upgrade.kind().family().equals("cooking")) {
            int progress = settings.getIntOr("cook_progress", 0), total = settings.getIntOr("cook_total", 200);
            graphics.fill(leftPos + x, topPos + 73, leftPos + x + (int) Math.clamp(52L * progress / Math.max(1, total), 0, 52), topPos + 76, 0xffeea837);
        }
    }
    @Override protected void extractLabels(GuiGraphicsExtractor graphics, int x, int y) {
        String mode = switch (menu.editMode()) { case 1 -> "Memory: left set / right clear"; case 2 -> "No-sort: click slots"; default -> ""; };
        if (!mode.isEmpty()) graphics.text(font, mode, 8, imageHeight - 12, 0xff315d6a, false);
    }
    @Override protected void extractSlot(GuiGraphicsExtractor graphics, Slot slot, int x, int y) {
        if (slot.container == menu.bag() && slot.getItem().getCount() > 999) {
            graphics.item(slot.getItem(), slot.x, slot.y);
            graphics.itemDecorations(font, slot.getItem().copyWithCount(1), slot.x, slot.y);
            graphics.pose().pushMatrix();
            graphics.pose().translate(slot.x + 17, slot.y + 11).scale(0.65f);
            String count = shortAmount(slot.getItem().getCount());
            graphics.text(font, count, -font.width(count), 0, 0xffffffff, true);
            graphics.pose().popMatrix();
        } else super.extractSlot(graphics, slot, x, y);
    }
    @Override protected List<Component> getTooltipFromContainerItem(ItemStack item) {
        List<Component> tooltip = new ArrayList<>(super.getTooltipFromContainerItem(item));
        if (item.getCount() > item.getMaxStackSize()) tooltip.add(Component.literal("Stored: " + java.text.NumberFormat.getIntegerInstance(Locale.ROOT).format(item.getCount())));
        return tooltip;
    }
    private boolean matchesSearch(ItemStack item) {
        if (item.isEmpty()) return false;
        var id = BuiltInRegistries.ITEM.getKey(item.getItem());
        String tooltip = query.contains("#") ? item.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(minecraft.level), minecraft.player, TooltipFlag.NORMAL)
                .stream().map(Component::getString).collect(java.util.stream.Collectors.joining(" ")) : "";
        return parsedQuery.matches(new com.kadamitas.fabricatedbackpacks.browser.BrowserQuery.SearchText(
                item.getHoverName().getString() + " " + id, id.getNamespace(), tooltip));
    }
    private void refreshStorageView() {
        String mask = "";
        if (!query.isBlank()) {
            var bits = new StringBuilder(menu.bag().getContainerSize());
            var memory = menu.bag().stack().getOrDefault(BagComponents.MEMORY, com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot.EMPTY);
            var ghosts = new java.util.HashMap<Integer, ItemStack>();
            for (var entry : memory.entries()) ghosts.put(entry.slot(), entry.create());
            for (int slot = 0; slot < menu.bag().getContainerSize(); slot++) {
                ItemStack item = menu.bag().getItem(slot);
                bits.append(matchesSearch(item.isEmpty() ? ghosts.getOrDefault(slot, ItemStack.EMPTY) : item) ? '1' : '0');
            }
            mask = bits.toString();
        }
        if (!sentMask.equals(mask)) {
            menu.storageView(mask);
            send("storage_view", 0, 0, mask);
            sentMask = mask;
        }
        for (int index = 0; index < menu.bag().getContainerSize(); index++) {
            int rank = menu.storageRank(index);
            var position = (com.kadamitas.fabricatedbackpacks.client.mixin.SlotPositionAccess) menu.slots.get(index);
            position.fabricatedBackpacks$x(rank < 0 ? -20000 : 8 + rank % menu.bag().columns() * 18);
            position.fabricatedBackpacks$y(rank < 0 ? -20000 : 32 + rank / menu.bag().columns() % BackpackMenu.VISIBLE_ROWS * 18);
        }
        noResults.visible = menu.filtering() && menu.filteredSize() == 0;
        if (storagePage != null) storagePage.active = menu.pages() > 1;
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        customClick = false;
        var captures = menu.filtering() ? new net.minecraft.nbt.ListTag() : menu.bag().settings().getListOrEmpty("captured_entities");
        for (int index = 0; index < captures.size(); index++) {
            var capture = captures.getCompoundOrEmpty(index);
            int row = capture.getIntOr("y", 0) - menu.page() * BackpackMenu.VISIBLE_ROWS;
            int rows = capture.getIntOr("height", 1);
            if (row >= BackpackMenu.VISIBLE_ROWS || row + rows <= 0) continue;
            if (isHovering(8 + capture.getIntOr("x", 0) * 18, 32 + Math.max(0, row) * 18,
                    capture.getIntOr("width", 1) * 18 - 2, (Math.min(BackpackMenu.VISIBLE_ROWS, row + rows) - Math.max(0, row)) * 18 - 2,
                    event.x(), event.y())) {
                beginCustomClick(event);
                if (event.button() == 1) send("release_mob", index, 0, "");
                return true;
            }
        }
        var resources = menu.filtering() ? List.<InstalledUpgrade>of() : resources();
        int firstColumn = menu.bag().columns() - resources.size() * 2;
        for (int index = 0; index < resources.size(); index++) if (isHovering(8 + (firstColumn + index * 2) * 18, 32, 34,
                Math.min(BackpackMenu.VISIBLE_ROWS, menu.bag().rows()) * 18 - 2, event.x(), event.y())) {
            beginCustomClick(event);
            send("resource_container", resources.get(index).slot(), 0, "");
            return true;
        }
        InstalledUpgrade upgrade = menu.selected().orElse(null);
        int firstFilter = upgrade == null ? 0 : ghostPage * filterPageSize(upgrade);
        if (upgrade != null) for (int index = firstFilter; index < Math.min(menu.bag().filterSlots(upgrade), firstFilter + filterPageSize(upgrade)); index++) {
            int local = index - firstFilter;
            if (isHovering(menu.storageWidth() + 51 + local % menu.bag().filterColumns(upgrade) * 18,
                    ghostY(upgrade) + local / menu.bag().filterColumns(upgrade) * 18, 16, 16, event.x(), event.y())) {
                beginCustomClick(event);
                if (event.button() == 0 && menu.getCarried().isEmpty()) {
                    com.kadamitas.fabricatedbackpacks.client.browser.RecipeBrowserClient.openForGhost(this, index);
                    return true;
                }
                send("ghost", index, event.button() == 1 ? 1 : 0, ""); return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }
    private void beginCustomClick(MouseButtonEvent event) {
        // Reset vanilla's last-slot/double-click state for these non-slot controls.
        // Otherwise returning a cursor item to its previous slot can collect it again.
        super.mouseClicked(event, false);
        customClick = true;
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (customClick) { customClick = false; return true; }
        return super.mouseReleased(event);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (search.isFocused() && event.key() != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) return search.keyPressed(event);
        return super.keyPressed(event);
    }
    private String selectionKey() { return menu.selectedSlot() + ":" + menu.selected().map(upgrade -> upgrade.kind().id()).orElse(""); }
    @Override protected void containerTick() {
        refreshStorageView();
        if (searchDebounce > 0 && --searchDebounce == 0) flushSearch();
        if (!selectionKey().equals(selectedKey)) { controlPage = 0; ghostPage = 0; auxiliaryPage = 0; rebuildWidgets(); }
        menu.selected().ifPresent(upgrade -> {
            var name = upgrade.stack().getHoverName();
            var heading = name.copy().withStyle(style -> style.withColor(0x302a21));
            if (upgradeHeading != null && !upgradeHeading.getMessage().equals(heading)) {
                upgradeHeading.setMessage(heading);
                upgradeHeading.setTooltip(Tooltip.create(name));
            }
            var settings = menu.bag().settings(upgrade);
            for (OptionButton option : optionButtons) {
                var optionSettings = option.action().startsWith("inception_") ? menu.preferences() : settings;
                String key = option.action().equals("toggle") ? "enabled" : option.action();
                String value = optionSettings.getStringOr(key, "");
                if (optionSettings.getBoolean(key).isPresent() || key.equals("enabled") || key.startsWith("inception_")
                        || key.equals("alchemy_all_missing") || key.startsWith("alchemy_match_")) value = optionSettings.getBooleanOr(key, true) ? "On" : "Off";
                String label = UpgradeControls.label(option.action()) + (value.isEmpty() ? "" : ": " + value);
                option.button().setMessage(Component.literal(font.plainSubstrByWidth(label, 80)));
                option.button().setTooltip(Tooltip.create(Component.literal(label)));
            }
        });
    }
    private void flushSearch() {
        if (!sentQuery.equals(query) && minecraft.player != null && minecraft.player.containerMenu == menu) {
            send("search", 0, 0, query);
            sentQuery = query;
        }
    }
    @Override public void removed() {
        flushSearch();
        super.removed();
    }
}
