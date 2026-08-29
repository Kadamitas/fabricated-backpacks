package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.domain.BackpackLayout;
import com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime;
import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
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
import java.util.Optional;

import com.kadamitas.fabricatedbackpacks.client.screen.BackpackIconButton.Icon;

public final class BackpackScreen extends AbstractContainerScreen<BackpackMenu> {
    // Vanilla permits at most128 changed-slot hashes per click packet. Leave room
    // for the backpack item and component-bearing slots that accompany a drag.
    private static final int MAX_DRAG_SLOTS = 120;
    private EditBox search;
    private Button storagePage;
    private BackpackIconButton settingsButton;
    private BackpackIconButton sortOrderButton;
    private BackpackIconButton searchButton;
    private StringWidget upgradeHeading;
    private StringWidget noResults;
    private String query = "";
    private int controlPage;
    private int ghostPage;
    private int fuelGhostPage;
    private int auxiliaryPage;
    private int upgradePage;
    private String selectedKey = "";
    private String layoutKey = "";
    private int requestedRows = -1;
    private int contentWidth;
    private int contentHeight;
    private boolean searchExpanded;
    private StringWidget backpackHeading;
    private Panel panel;
    private final Map<Integer, BackpackIconButton> upgradeTabs = new HashMap<>();
    private final List<OptionButton> optionButtons = new ArrayList<>();
    private final ShiftTooltips optionTooltips = new ShiftTooltips();
    private final Map<Integer, net.minecraft.world.entity.LivingEntity> capturedPreviews = new HashMap<>();
    private String captureFingerprint = "";
    private boolean customClick;
    private boolean cancelNextRelease;
    private boolean compactTabs;
    private boolean searchInitialized;
    private String sentQuery = "";
    private int searchDebounce;
    private String sentMask = "";
    private com.kadamitas.fabricatedbackpacks.browser.BrowserQuery parsedQuery = com.kadamitas.fabricatedbackpacks.browser.BrowserQuery.parse("");
    private record OptionButton(String action, BackpackIconButton button) {}
    private record FilterGrid(int firstIndex, int slots, int y, int rows, int pageY) {
        int pageSize(int columns) { return columns * Math.max(1, rows); }
        int pages(int columns) { return Math.max(1, Math.ceilDiv(slots, pageSize(columns))); }
        FilterGrid at(int panelY) { return new FilterGrid(firstIndex, slots, panelY + y, rows, panelY + pageY); }
    }
    private record Panel(int x, int y, int width, int height, int inventoryY, FilterGrid filters, FilterGrid fuelFilters,
                         int controlsY, int inventoryPageY, int controlsPageY,
                         int inventoryRows, int controlColumns, int controlsPerPage, boolean furnaceLayout) {}

    public BackpackScreen(BackpackMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, menu.imageWidth(), menu.imageHeight());
    }
    @Override protected void init() {
        cancelQuickCraft();
        super.init();
        optionButtons.clear();
        optionTooltips.clear();
        upgradeTabs.clear();
        storagePage = null;
        upgradeHeading = null;
        searchButton = null;
        int railSize = menu.bag().upgrades().getContainerSize() > 10 ? 9 : 10;
        int railPages = Math.max(1, Math.ceilDiv(menu.bag().upgrades().getContainerSize(), railSize));
        upgradePage = Math.clamp(upgradePage, 0, railPages - 1);
        var visibleUpgrades = menu.bag().installedUpgrades().stream().filter(upgrade -> upgrade.slot() / railSize == upgradePage).toList();
        contentHeight = Math.max(menu.imageHeight(), 35 + visibleUpgrades.size() * 20);
        compactTabs = false;
        panel = menu.selected().map(this::layoutPanel).orElse(null);
        boolean selectedTabVisible = visibleUpgrades.stream().anyMatch(upgrade -> upgrade.slot() == menu.selectedSlot());
        if (panel == null) {
            compactTabs = 29 + Math.max(0, visibleUpgrades.size() - 1) * 25 + 22 > contentHeight;
        } else if (!selectedTabVisible
                || 35 + panel.height() + Math.max(0, visibleUpgrades.size() - 1) * 25 > contentHeight) {
            compactTabs = true;
            panel = menu.selected().map(this::layoutPanel).orElse(null);
        }
        contentWidth = panel == null ? menu.layout().tabX() + 22 : panel.x() + panel.width();
        leftPos = (width - contentWidth) / 2;
        topPos = (height - contentHeight) / 2;
        int storageX = menu.storageX();
        if (!searchInitialized) {
            query = menu.preferences().getBooleanOr("keep_search", true) ? menu.bag().settings().getStringOr("last_search", "") : "";
            searchInitialized = true;
            sentQuery = query;
            searchExpanded = !query.isBlank();
        }
        boolean showSearch = menu.bag().columns() > 9 || searchExpanded || !query.isBlank();
        int orderX = menu.layout().tabX() - 17;
        int headerWidth = orderX - (showSearch ? 26 : 13) - storageX - 3;
        backpackHeading = heading(title, storageX, 5, headerWidth, BackpackStyle.TITLE_TEXT);
        noResults = addRenderableWidget(new StringWidget(leftPos + storageX + 4, topPos + menu.storageY() + 8, menu.storageWidth() - 24, font.lineHeight,
                Component.literal("No matching items").withStyle(style -> style.withColor(BackpackStyle.MUTED_TEXT).withoutShadow()), font)
                .setMaxWidth(menu.storageWidth() - 24));
        search = addRenderableWidget(new EditBox(font, leftPos + storageX, topPos + 4, headerWidth, 11,
                Component.translatable("screen.fabricated_backpacks.search")));
        search.setMaxLength(120);
        search.setValue(query);
        search.setHint(Component.literal("Search items"));
        search.setTooltip(Tooltip.create(Component.translatable("screen.fabricated_backpacks.search_hint")));
        search.visible = searchExpanded;
        backpackHeading.visible = !searchExpanded;
        parsedQuery = com.kadamitas.fabricatedbackpacks.browser.BrowserQuery.parse(query);
        search.setResponder(value -> { query = value; parsedQuery = com.kadamitas.fabricatedbackpacks.browser.BrowserQuery.parse(value); searchDebounce = 5; refreshStorageView(); });
        if (showSearch) searchButton = smallIcon("Search", orderX - 26, 4, Icon.SEARCH, this::toggleSearch);
        smallIcon("Sort", orderX - 13, 4, Icon.TRANSFER_UP, this::sort);
        sortOrderButton = smallIcon("Sort order", orderX, 4, Icon.SORT_ORDER, () -> menuButton(10));
        settingsButton = addRenderableWidget(new BackpackIconButton(leftPos + menu.layout().tabX(), topPos + 4,
                22, 22, Component.literal("Prefs"), Icon.GEAR, () -> minecraft.gui.setScreen(new BackpackSettingsScreen(this))));
        int bar = menu.layout().inventoryTitleY();
        int actionsX = menu.inventoryX() + 124;
        if (menu.bag().rows() > menu.visibleRows()) {
            actionsX -= 13;
            storagePage = smallIcon(">", menu.inventoryX() + 150, bar, Icon.NEXT, () -> menuButton(1));
        }
        if (menu.bag().getContainerSize() > 27) {
            smallIcon("Store matching", actionsX, bar, Icon.TRANSFER_UP,
                    () -> send("bulk_store", 0, minecraft.hasShiftDown() ? 1 : 0, ""));
            smallIcon("Take matching", actionsX + 13, bar, Icon.TRANSFER_DOWN,
                    () -> send("bulk_take", 0, minecraft.hasShiftDown() ? 1 : 0, ""));
            smallIcon("Items", actionsX + 26, bar, Icon.ITEMS, this::openBrowser);
        }
        menu.setUpgradeWindow(upgradePage * railSize, railSize);
        if (railPages > 1) icon("Upgrades " + (upgradePage + 1) + "/" + railPages, 4, contentHeight - 18, Icon.NEXT,
                () -> { upgradePage = (upgradePage + 1) % railPages; rebuildWidgets(); });
        int tabY = 29;
        for (InstalledUpgrade upgrade : visibleUpgrades) {
            int slot = upgrade.slot();
            boolean expanded = !compactTabs && slot == menu.selectedSlot();
            BackpackIconButton tab = new BackpackIconButton(leftPos + menu.layout().tabX() + (expanded ? 3 : 0),
                    topPos + tabY + (expanded ? 3 : 0), expanded ? 20 : 22, expanded ? 20 : compactTabs ? 20 : 22,
                    Component.literal("Upgrade " + (slot + 1) + ": " + upgrade.stack().getHoverName().getString()),
                    Icon.GENERIC, () -> { controlPage = 0; menuButton(1000 + slot); });
            tab.setItem(upgrade.stack());
            tab.setSelected(slot == menu.selectedSlot()).setEmbedded(expanded);
            upgradeTabs.put(slot, addRenderableWidget(tab));
            tabY += expanded ? panel.height() + 3 : compactTabs ? 20 : 25;
        }
        menu.selected().ifPresent(upgrade -> {
            if (panel.width() >= 70) {
                upgradeHeading = heading(panelTitle(upgrade), panel.x() + 24, panel.y() + 8,
                        panel.width() - 30, BackpackStyle.PANEL_TEXT);
                upgradeHeading.setTooltip(Tooltip.create(upgrade.stack().getHoverName()));
            }
            layoutAuxiliary(upgrade);
            addControls(upgrade);
        });
        layoutInventory();
        selectedKey = selectionKey();
        layoutKey = layoutKey();
        refreshStorageView();
        requestRows();
        optionTooltips.refresh(minecraft);
    }

    private void toggleSearch() {
        searchExpanded = !searchExpanded;
        search.visible = searchExpanded;
        backpackHeading.visible = !searchExpanded;
        setFocused(searchExpanded ? search : null);
        search.setFocused(searchExpanded);
    }

    private void requestRows() {
        int rows = BackpackLayout.rowsForViewport(menu.bag().rows(), height, menu.bag().upgrades().getContainerSize());
        if (requestedRows != rows) {
            requestedRows = rows;
            menuButton(200 + rows);
        }
    }

    private String layoutKey() {
        return menu.page() + ":" + menu.visibleRows() + ":" + menu.imageWidth() + ":" + menu.bag().installedUpgrades().stream()
                .map(upgrade -> upgrade.slot() + "=" + upgrade.kind().id() + ":" + menu.bag().inventorySlots(upgrade)
                        + ":" + menu.bag().inventoryColumns(upgrade) + ":" + menu.bag().filterSlots(upgrade)
                        + ":" + menu.bag().filterColumns(upgrade) + ":" + inputFilterCount(upgrade)).collect(java.util.stream.Collectors.joining(","));
    }

    private void layoutInventory() {
        for (Slot slot : menu.slots) {
            if (slot.container == menu.bag().upgrades()) {
                int railSize = menu.bag().upgrades().getContainerSize() > 10 ? 9 : 10;
                move(slot, menu.layout().upgradeSlotX(), menu.layout().upgradeSlotY(slot.getContainerSlot() % railSize));
            }
            else if (slot.container == minecraft.player.getInventory()) {
                int index = slot.getContainerSlot();
                if (index < 9) move(slot, menu.inventoryX() + index * 18, menu.inventoryY() + 58);
                else if (index < 36) move(slot, menu.inventoryX() + (index - 9) % 9 * 18, menu.inventoryY() + (index - 9) / 9 * 18);
            }
        }
    }

    private static void move(Slot slot, int x, int y) {
        var position = (com.kadamitas.fabricatedbackpacks.client.mixin.SlotPositionAccess) slot;
        position.fabricatedBackpacks$x(x);
        position.fabricatedBackpacks$y(y);
    }
    private StringWidget heading(Component text, int x, int y, int width, int color) {
        final int maxHeadingWidth = width;
        var label = new StringWidget(leftPos + x, topPos + y, width, font.lineHeight,
                text.copy().withStyle(style -> style.withColor(color).withoutShadow()), font) {
            @Override public void visitLines(ActiveTextCollector collector) {
                var line = font.width(getMessage()) > maxHeadingWidth
                        ? ComponentRenderUtils.clipText(getMessage(), font, maxHeadingWidth) : getMessage().getVisualOrderText();
                // Native clipping appends an unstyled ellipsis, so apply the no-shadow rule after clipping too.
                collector.accept(getX(), getY() + (getHeight() - font.lineHeight) / 2,
                        sink -> line.accept((index, style, codePoint) -> sink.accept(index,
                                (style.getColor() == null ? style.withColor(color) : style).withoutShadow(), codePoint)));
            }
        }.setMaxWidth(width);
        label.setTooltip(Tooltip.create(text));
        return addRenderableWidget(label);
    }
    private static Component panelTitle(InstalledUpgrade upgrade) {
        return upgrade.stack().has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)
                ? upgrade.stack().getHoverName() : Component.literal(UpgradeControls.title(upgrade.kind()));
    }
    private void addControls(InstalledUpgrade upgrade) {
        if (BackpackMenu.isWorkstation(upgrade.kind())) {
            icon("Open station", panel.x() + 6, panel.y() + 24, Icon.ITEMS, () -> send("workstation", 0, 0, ""));
            return;
        }
        int inventoryPages = Math.max(1, Math.ceilDiv(menu.bag().inventorySlots(upgrade), auxiliaryPageSize(upgrade)));
        if (inventoryPages > 1) {
            smallIcon("Previous slots " + (auxiliaryPage + 1) + "/" + inventoryPages,
                    panel.x() + panel.width() - 27, panel.inventoryPageY(), Icon.PREVIOUS,
                    () -> { auxiliaryPage = Math.floorMod(auxiliaryPage - 1, inventoryPages); rebuildWidgets(); });
            smallIcon("Next slots " + (auxiliaryPage + 1) + "/" + inventoryPages,
                    panel.x() + panel.width() - 14, panel.inventoryPageY(), Icon.NEXT,
                    () -> { auxiliaryPage = (auxiliaryPage + 1) % inventoryPages; rebuildWidgets(); });
        }
        int filterPages = panel.filters().pages(filterColumns(upgrade));
        String filterLabel = panel.fuelFilters().slots() > 0 ? "Input filters " : "Filters ";
        if (filterPages > 1) icon(filterLabel + (ghostPage + 1) + "/" + filterPages,
                panel.x() + panel.width() - 23, panel.filters().pageY(), Icon.NEXT,
                () -> { ghostPage = (ghostPage + 1) % filterPages; rebuildWidgets(); });
        int fuelPages = panel.fuelFilters().pages(filterColumns(upgrade));
        if (fuelPages > 1) icon("Fuel filters " + (fuelGhostPage + 1) + "/" + fuelPages,
                panel.x() + panel.width() - 23, panel.fuelFilters().pageY(), Icon.NEXT,
                () -> { fuelGhostPage = (fuelGhostPage + 1) % fuelPages; rebuildWidgets(); });
        List<String> actions = UpgradeControls.actions(menu.bag(), upgrade);
        int visible = panel.controlsPerPage();
        int pageCount = Math.max(1, Math.ceilDiv(actions.size(), visible));
        controlPage = Math.min(controlPage, pageCount - 1);
        for (int i = 0; i < visible && controlPage * visible + i < actions.size(); i++) {
            String action = actions.get(controlPage * visible + i);
            var settings = action.startsWith("inception_") ? menu.preferences() : menu.bag().settings(upgrade);
            var presentation = UpgradeControls.presentation(action, upgrade.kind(), settings);
            BackpackIconButton button = icon(presentation.label(), panel.x() + 6 + i % panel.controlColumns() * 18,
                    panel.controlsY() + i / panel.controlColumns() * 18, 16, presentation.icon(),
                    () -> {
                        if (action.equals("tags") || action.equals("input_tags")) minecraft.gui.setScreen(new FilterTagsScreen(this, action.equals("input_tags")));
                        else if (action.equals("fluids")) minecraft.gui.setScreen(new VoidFluidFiltersScreen(this));
                        else if (action.equals("slot_rules")) minecraft.gui.setScreen(new SlotRulesScreen(this, upgrade));
                        else send(action.startsWith("inception_") ? "setting" : "upgrade", 0, 0, action);
                    });
            button.setAutomaticTooltip(false);
            button.setSelected(presentation.selected());
            optionTooltips.add(button, Component.literal(UpgradeControls.help(action, upgrade.kind(), settings)));
            optionButtons.add(new OptionButton(action, button));
        }
        if (pageCount > 1) icon("More " + (controlPage + 1) + "/" + pageCount,
                panel.x() + panel.width() - 23, panel.controlsPageY(), panel.furnaceLayout() ? Icon.GEAR : Icon.NEXT,
                () -> { controlPage = (controlPage + 1) % pageCount; rebuildWidgets(); });
    }
    private Panel layoutPanel(InstalledUpgrade upgrade) {
        int inventoryRows = Math.min(3, Math.ceilDiv(menu.bag().inventorySlots(upgrade), inventoryColumns(upgrade)));
        int filterRows = Math.min(3, Math.ceilDiv(inputFilterCount(upgrade), filterColumns(upgrade)));
        int fuelRows = Math.min(3, Math.ceilDiv(fuelFilterCount(upgrade), filterColumns(upgrade)));
        int controlRows = upgrade.kind().family().equals("cooking") ? 1 : 2;
        boolean furnaceLayout = upgrade.kind().family().equals("cooking") && panelWidth() >= 78;
        Panel result;
        while (true) {
            result = composePanel(upgrade, inventoryRows, filterRows, fuelRows, controlRows, furnaceLayout);
            if (result.height() <= contentHeight - 35) break;
            if (inventoryRows > 1) inventoryRows--;
            else if (filterRows > 1 && (filterRows > fuelRows || fuelRows <= 1)) filterRows--;
            else if (fuelRows > 1) fuelRows--;
            else if (controlRows > 1) controlRows--;
            else if (furnaceLayout) furnaceLayout = false;
            else break;
        }
        int railSize = menu.bag().upgrades().getContainerSize() > 10 ? 9 : 10;
        int earlier = (int) menu.bag().installedUpgrades().stream()
                .filter(candidate -> candidate.slot() / railSize == upgradePage && candidate.slot() < upgrade.slot()).count();
        int y = compactTabs ? Math.clamp(29 + earlier * 20, 29, Math.max(29, contentHeight - result.height() - 6)) : 29 + earlier * 25;
        return new Panel(result.x(), y, result.width(), result.height(), y + result.inventoryY(),
                result.filters().at(y), result.fuelFilters().at(y),
                y + result.controlsY(), y + result.inventoryPageY(), y + result.controlsPageY(),
                result.inventoryRows(), result.controlColumns(), result.controlsPerPage(), result.furnaceLayout());
    }
    private int panelWidth() {
        int available = width - 8 - panelStartX();
        if (available >= menu.panelWidth()) return menu.panelWidth();
        return Math.max(1, (available - 12) / 18) * 18 + 12;
    }
    private int panelStartX() { return menu.panelX() + (compactTabs ? 22 : 0); }
    private int inventoryColumns(InstalledUpgrade upgrade) {
        return Math.min(menu.bag().inventoryColumns(upgrade), Math.max(1, (panelWidth() - 12) / 18));
    }
    private int filterColumns(InstalledUpgrade upgrade) {
        return Math.min(menu.bag().filterColumns(upgrade), Math.max(1, (panelWidth() - 12) / 18));
    }
    private static boolean splitCookingFilters(InstalledUpgrade upgrade) {
        return upgrade.kind().family().equals("cooking") && upgrade.kind().filterSlots() > 0;
    }
    private int inputFilterCount(InstalledUpgrade upgrade) {
        return splitCookingFilters(upgrade) ? menu.bag().cookingInputFilters(upgrade) : menu.bag().filterSlots(upgrade);
    }
    private int fuelFilterCount(InstalledUpgrade upgrade) {
        return splitCookingFilters(upgrade) ? menu.bag().cookingFuelFilters(upgrade) : 0;
    }
    private Panel composePanel(InstalledUpgrade upgrade, int inventoryRows, int filterRows, int fuelRows,
                               int maxControlRows, boolean furnaceLayout) {
        int width = panelWidth();
        int columns = Math.max(1, (width - 12) / 18);
        int actionCount = UpgradeControls.actions(menu.bag(), upgrade).size();
        boolean cooking = upgrade.kind().family().equals("cooking");
        boolean furnaceControlPage = cooking && furnaceLayout;
        boolean inlineControlPage = cooking && !furnaceControlPage && columns > 1 && actionCount > columns;
        int controlRows = Math.min(maxControlRows, Math.ceilDiv(actionCount, columns));
        int controlsPerPage = Math.max(1, maxControlRows * columns - (inlineControlPage ? 1 : 0));
        boolean inventoryPages = !upgrade.kind().family().equals("cooking")
                && menu.bag().inventorySlots(upgrade) > Math.max(1, inventoryRows) * inventoryColumns(upgrade);
        int inputFilters = inputFilterCount(upgrade), fuelFilters = fuelFilterCount(upgrade);
        boolean filterPages = inputFilters > Math.max(1, filterRows) * filterColumns(upgrade);
        boolean fuelPages = fuelFilters > Math.max(1, fuelRows) * filterColumns(upgrade);
        boolean controlPages = actionCount > controlsPerPage;
        boolean jukebox = upgrade.kind().family().equals("jukebox");
        int y = 24;
        int inventoryY = y, filterY = y, fuelY = y, controlsY = y;
        int inventoryPageY = y, filterPageY = y, fuelPageY = y, controlsPageY = y;
        if (BackpackMenu.isWorkstation(upgrade.kind()))
            return new Panel(panelStartX(), 0, width, 50, y,
                    new FilterGrid(0, 0, y, 0, y), new FilterGrid(0, 0, y, 0, y),
                    y, y, y, 0, columns, controlsPerPage, false);
        if (jukebox && inventoryRows > 0) {
            inventoryY = y;
            y += inventoryRows * 18 + 4;
            inventoryPageY = y;
            if (inventoryPages) y += 16;
            y += 4;
        }
        if (controlRows > 0) {
            controlsY = y;
            y += controlRows * 18 + 4;
            controlsPageY = inlineControlPage ? controlsY : y;
            if (controlPages && !inlineControlPage && !furnaceControlPage) y += 16;
        }
        if (filterRows > 0) {
            filterY = y;
            y += filterRows * 18 + 4;
            filterPageY = y;
            if (filterPages) y += 16;
        }
        if (!jukebox && inventoryRows > 0) {
            inventoryY = y;
            y += (cooking ? furnaceLayout ? 58 : Math.ceilDiv(3, inventoryColumns(upgrade)) * 18 : inventoryRows * 18) + 4;
            inventoryPageY = y;
            if (inventoryPages) y += 16;
        }
        // The furnace has a free square below its result; keep paging out of the four filter controls.
        if (furnaceControlPage) controlsPageY = inventoryY + 38;
        if (fuelRows > 0) {
            fuelY = y;
            y += fuelRows * 18 + 4;
            fuelPageY = y;
            if (fuelPages) y += 16;
        }
        return new Panel(panelStartX(), 0, width, Math.max(50, y + 6), inventoryY,
                new FilterGrid(0, inputFilters, filterY, filterRows, filterPageY),
                new FilterGrid(inputFilters, fuelFilters, fuelY, fuelRows, fuelPageY), controlsY,
                inventoryPageY, controlsPageY, inventoryRows, columns, controlsPerPage, furnaceLayout);
    }

    private int auxiliaryPageSize(InstalledUpgrade upgrade) {
        return upgrade.kind().family().equals("cooking") ? 3 : inventoryColumns(upgrade) * Math.max(1, panel.inventoryRows());
    }
    private void layoutAuxiliary(InstalledUpgrade upgrade) {
        int pageSize = auxiliaryPageSize(upgrade);
        int pages = Math.max(1, Math.ceilDiv(menu.bag().inventorySlots(upgrade), pageSize));
        auxiliaryPage = Math.clamp(auxiliaryPage, 0, pages - 1);
        ghostPage = Math.clamp(ghostPage, 0, panel.filters().pages(filterColumns(upgrade)) - 1);
        fuelGhostPage = Math.clamp(fuelGhostPage, 0, panel.fuelFilters().pages(filterColumns(upgrade)) - 1);
        menu.setAuxiliaryWindow(auxiliaryPage * pageSize, pageSize);
        int columns = inventoryColumns(upgrade);
        for (int index = 0; index < menu.auxiliaryCount(); index++) {
            int local = index % pageSize;
            int x = panel.x() + 6 + local % columns * 18;
            int y = panel.inventoryY() + local / columns * 18;
            if (panel.furnaceLayout() && index < 3) {
                x = panel.x() + (index == 2 ? panel.width() - 24 : 6);
                y = panel.inventoryY() + (index == 1 ? 36 : index == 2 ? 18 : 0);
            }
            move(menu.slots.get(menu.auxiliaryStart() + index), x, y);
        }
    }
    private BackpackIconButton icon(String label, int x, int y, Icon glyph, Runnable action) {
        return icon(label, x, y, 14, glyph, action);
    }
    private BackpackIconButton smallIcon(String label, int x, int y, Icon glyph, Runnable action) {
        return icon(label, x, y, 12, glyph, action);
    }
    private BackpackIconButton icon(String label, int x, int y, int size, Icon glyph, Runnable action) {
        BackpackIconButton button = new BackpackIconButton(leftPos + x, topPos + y, size, size,
                Component.literal(label), glyph, action);
        button.setTooltip(Tooltip.create(Component.literal(label)));
        return addRenderableWidget(button);
    }
    private void menuButton(int id) { minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id); }
    private void sort() {
        menuButton(switch (menu.preferences().getStringOr("sort_order", "name")) {
            case "count" -> 3;
            case "mod" -> 4;
            case "tags" -> 5;
            default -> 0;
        });
    }
    private void send(String action, int index, int value, String text) { ClientPlayNetworking.send(new MenuAction(menu.containerId, action, index, value, text)); }
    private void sendGlobal(String action) { ClientPlayNetworking.send(new MenuAction(-1, action, 0, 0, "")); }
    private void openBrowser() { com.kadamitas.fabricatedbackpacks.client.browser.RecipeBrowserClient.open(this); }

    /** Visible interaction bounds in screen coordinates, shared by rendering, input and acceptance tests. */
    public Optional<ScreenRectangle> ghostBounds(int physicalGhostIndex) {
        return menu.selected().flatMap(upgrade -> {
            if (panel == null || physicalGhostIndex < 0 || physicalGhostIndex >= menu.bag().filterSlots(upgrade)) return Optional.empty();
            boolean fuel = panel.fuelFilters().slots() > 0 && physicalGhostIndex >= panel.fuelFilters().firstIndex();
            FilterGrid grid = fuel ? panel.fuelFilters() : panel.filters();
            int columns = filterColumns(upgrade);
            int pageSize = grid.pageSize(columns);
            int first = grid.firstIndex() + (fuel ? fuelGhostPage : ghostPage) * pageSize;
            int local = physicalGhostIndex - first;
            if (local < 0 || local >= pageSize || physicalGhostIndex >= grid.firstIndex() + grid.slots()) return Optional.empty();
            return Optional.of(new ScreenRectangle(leftPos + panel.x() + 6 + local % columns * 18,
                    topPos + grid.y() + local / columns * 18, 16, 16));
        });
    }

    public Optional<ScreenRectangle> resourceBounds(int upgradeSlot) {
        if (menu.filtering()) return Optional.empty();
        List<InstalledUpgrade> resources = resources();
        int firstColumn = menu.bag().columns() - resources.size() * 2;
        for (int index = 0; index < resources.size(); index++) if (resources.get(index).slot() == upgradeSlot) {
            return Optional.of(new ScreenRectangle(leftPos + menu.storageX() + (firstColumn + index * 2) * 18 + 9,
                    topPos + menu.storageY(), 16, menu.visibleRows() * 18 - 2));
        }
        return Optional.empty();
    }

    public Optional<ScreenRectangle> upgradeTabBounds(int upgradeSlot) {
        return Optional.ofNullable(upgradeTabs.get(upgradeSlot))
                .filter(button -> button.visible)
                .map(button -> new ScreenRectangle(button.getX(), button.getY(), button.getWidth(), button.getHeight()));
    }

    public Optional<ScreenRectangle> upgradePanelBounds() {
        return panel == null ? Optional.empty() : Optional.of(new ScreenRectangle(
                leftPos + panel.x(), topPos + panel.y(), panel.width(), panel.height()));
    }

    private static boolean contains(ScreenRectangle bounds, double x, double y) {
        return x >= bounds.left() && x < bounds.right() && y >= bounds.top() && y < bounds.bottom();
    }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        refreshLayout();
        super.extractBackground(graphics, mouseX, mouseY, delta);
        var layout = menu.layout();
        BackpackStyle.frame(graphics, leftPos + layout.storagePanelX(), topPos,
                menu.storageWidth(), layout.inventoryTitleY() + 13, BackpackStyle.Surface.BODY);
        BackpackStyle.frame(graphics, leftPos + menu.inventoryX() - 8, topPos + layout.inventoryTitleY() + 8,
                176, 88, BackpackStyle.Surface.BODY);
        BackpackStyle.frame(graphics, leftPos + layout.storagePanelX() + 3, topPos + 3,
                menu.storageWidth() - 6, 13, BackpackStyle.Surface.TITLE);
        BackpackStyle.frame(graphics, leftPos + layout.storagePanelX() + 3, topPos + layout.inventoryTitleY(),
                menu.storageWidth() - 6, 12, BackpackStyle.Surface.TITLE);
        if (menu.bag().upgrades().getContainerSize() > 0) {
            int count = Math.min(menu.bag().upgrades().getContainerSize() > 10 ? 9 : 10,
                    menu.bag().upgrades().getContainerSize() - upgradePage * 9);
            BackpackStyle.frame(graphics, leftPos, topPos + 2, 24, count * 16 + 8, BackpackStyle.Surface.RAIL);
        }
        if (panel != null) {
            BackpackStyle.frame(graphics, leftPos + panel.x(), topPos + panel.y(), panel.width(), panel.height(), BackpackStyle.Surface.PANEL);
            if (compactTabs) menu.selected().ifPresent(upgrade -> graphics.fakeItem(upgrade.stack(), leftPos + panel.x() + 4, topPos + panel.y() + 5));
        }
        for (Slot slot : menu.slots) if (slot.isActive()) {
            if (slot.container == menu.bag().upgrades() && !slot.hasItem())
                BackpackStyle.emptyUpgradeSlot(graphics, leftPos + slot.x, topPos + slot.y);
            else slotBackground(graphics, slot.x, slot.y, false);
        }
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
                            graphics.fill(leftPos + display.x, topPos + display.y, leftPos + display.x + 16, topPos + display.y + 16, 0x88888785);
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
        for (InstalledUpgrade upgrade : resources) {
            ScreenRectangle bounds = resourceBounds(upgrade.slot()).orElseThrow();
            int x = bounds.left(), y = bounds.top(), width = bounds.width(), height = bounds.height();
            boolean tank = upgrade.kind() == UpgradeKind.TANK;
            long amount = tank ? ResourceRuntime.tankStoredMb(menu.bag(), upgrade.slot()) : ResourceRuntime.batteryStored(menu.bag(), upgrade.slot());
            long capacity = tank ? ResourceRuntime.tankCapacityMb(menu.bag(), upgrade.slot()) : ResourceRuntime.batteryCapacity(menu.bag(), upgrade.slot());
            var fluid = tank ? ResourceRuntime.tankFluid(menu.bag(), upgrade.slot()) : null;
            boolean experience = tank && !fluid.isBlank() && fluid.getFluid() == com.kadamitas.fabricatedbackpacks.resource.ResourceComponents.EXPERIENCE;
            int color = experience ? 0xff87d64a : tank && !fluid.isBlank() ? FluidVariantRendering.getColor(fluid) | 0xff000000 : 0xffeeb94e;
            graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xff282522);
            graphics.fill(x, y, x + width, y + height, tank ? 0xff555b5c : 0xff252528);
            int fill = amount == 0 ? 0 : (int) Math.clamp((double) amount / Math.max(1, capacity) * height, 1, height);
            if (fill > 0) graphics.fill(x + 2, y + height - fill, x + width - 2, y + height, color);
            for (int line = 3; line < height - 2; line += 8) {
                graphics.fill(x + 1, y + line, x + 4, y + line + 1, 0xffdbc16a);
                graphics.fill(x + width - 4, y + line, x + width - 1, y + line + 1, 0xffdbc16a);
                graphics.fill(x + 4, y + line, x + width - 4, y + line + 1, 0x225e6869);
            }
            graphics.fill(x + 1, y + 1, x + 2, y + height - 1, 0xffa5acad);
            if (contains(bounds, mouseX, mouseY)) {
                String name = experience ? "Liquid experience" : tank ? fluid.isBlank() ? "Empty tank" : FluidVariantRendering.getTooltip(fluid).stream()
                        .findFirst().map(Component::getString).orElse("Fluid") : "Stored energy";
                graphics.setTooltipForNextFrame(font, Component.literal(name + ": " + amount + " / " + capacity
                        + (tank ? " mB" : " E") + " — click with a container to transfer"), mouseX, mouseY);
            }
        }
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
            int row = capture.getIntOr("y", 0) - menu.page() * menu.visibleRows();
            int rows = capture.getIntOr("height", 1);
            if (row >= menu.visibleRows() || row + rows <= 0) continue;
            int x = menu.storageX() + capture.getIntOr("x", 0) * 18;
            int y = menu.storageY() + Math.max(0, row) * 18;
            int width = capture.getIntOr("width", 1) * 18 - 2;
            int height = (Math.min(menu.visibleRows(), row + rows) - Math.max(0, row)) * 18 - 2;
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
    private void slotBackground(GuiGraphicsExtractor graphics, int x, int y, boolean ghost) {
        BackpackStyle.slot(graphics, leftPos + x, topPos + y, ghost);
    }
    private void drawUpgrade(GuiGraphicsExtractor graphics, InstalledUpgrade upgrade, int mouseX, int mouseY) {
        int x = panel.x() + 6;
        var settings = menu.bag().settings(upgrade);
        for (int index = 0; index < menu.bag().filterSlots(upgrade); index++) {
            ScreenRectangle bounds = ghostBounds(index).orElse(null);
            if (bounds == null) continue;
            int gx = bounds.left() - leftPos;
            int gy = bounds.top() - topPos;
            slotBackground(graphics, gx, gy, true);
            ItemStack ghost = menu.bag().ghost(upgrade, index);
            if (!ghost.isEmpty()) graphics.fakeItem(ghost, leftPos + gx, topPos + gy);
            if (contains(bounds, mouseX, mouseY)) graphics.setTooltipForNextFrame(font,
                    ghost.isEmpty() ? Component.translatable("screen.fabricated_backpacks.ghost_help") : ghost.getHoverName(), mouseX, mouseY);
        }
        if (upgrade.kind().family().equals("jukebox") && settings.getBooleanOr("playing", false)) {
            int active = settings.getIntOr("active_slot", -1);
            int first = auxiliaryPage * auxiliaryPageSize(upgrade);
            if (active >= first && active < first + auxiliaryPageSize(upgrade)) {
                int local = active - first;
                graphics.outline(leftPos + x + local % inventoryColumns(upgrade) * 18 - 1,
                        topPos + panel.inventoryY() + local / inventoryColumns(upgrade) * 18 - 1, 18, 18, 0xff54db80);
            }
            long start = settings.getLongOr("song_started", 0), end = settings.getLongOr("song_finish", 1);
            long now = minecraft.level == null ? start : minecraft.level.getGameTime();
            int barWidth = panel.width() - 16;
            int length = (int) Math.clamp(barWidth * (now - start) / Math.max(1, end - start), 0, barWidth);
            graphics.fill(leftPos + x, topPos + panel.controlsY() - 5, leftPos + x + length, topPos + panel.controlsY() - 3, 0xff4d8f65);
        }
        if (panel.furnaceLayout()) {
            int progress = settings.getIntOr("cook_progress", 0), total = settings.getIntOr("cook_total", 200);
            int arrowX = leftPos + x + 23, arrowY = topPos + panel.inventoryY() + 21;
            graphics.fill(arrowX, arrowY + 3, arrowX + 19, arrowY + 8, 0xff635440);
            graphics.fill(arrowX + 14, arrowY, arrowX + 17, arrowY + 11, 0xff635440);
            graphics.fill(arrowX, arrowY + 4, arrowX + (int) Math.clamp(19L * progress / Math.max(1, total), 0, 19), arrowY + 7, 0xffffba43);
            int fireX = leftPos + x + 4, fireY = topPos + panel.inventoryY() + 21;
            boolean burning = settings.getBooleanOr("burning", false);
            graphics.fill(fireX + 2, fireY, fireX + 6, fireY + 9, burning ? 0xffd86825 : 0xff635440);
            graphics.fill(fireX, fireY + 4, fireX + 8, fireY + 10, burning ? 0xffdc8328 : 0xff635440);
            graphics.fill(fireX + 2, fireY + 6, fireX + 6, fireY + 11, burning ? 0xffffd26a : 0xff8d7960);
        }
    }
    @Override protected void extractLabels(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.text(font, playerInventoryTitle, menu.inventoryX(), menu.layout().inventoryTitleY() + 2, BackpackStyle.TITLE_TEXT, false);
    }
    @Override protected void extractSlot(GuiGraphicsExtractor graphics, Slot slot, int x, int y) {
        if (slot.container == menu.bag() && slot.getItem().getCount() > 999) {
            graphics.item(slot.getItem(), slot.x, slot.y);
            graphics.itemDecorations(font, slot.getItem().copyWithCount(1), slot.x, slot.y);
            String count = Integer.toString(slot.getItem().getCount());
            float scale = Math.min(0.65f, 15f / Math.max(1, font.width(count)));
            graphics.pose().pushMatrix();
            graphics.pose().translate(slot.x + 17, slot.y + 17 - font.lineHeight * scale).scale(scale);
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
            cancelQuickCraft();
            menu.storageView(mask);
            send("storage_view", 0, 0, mask);
            sentMask = mask;
        }
        for (int index = 0; index < menu.bag().getContainerSize(); index++) {
            int rank = menu.storageRank(index);
            move(menu.slots.get(index), rank < 0 ? -20000 : menu.storageX() + rank % menu.bag().columns() * 18,
                    rank < 0 ? -20000 : menu.storageY() + rank / menu.bag().columns() % menu.visibleRows() * 18);
        }
        noResults.visible = menu.filtering() && menu.filteredSize() == 0;
        if (storagePage != null) {
            storagePage.active = menu.pages() > 1;
            storagePage.setTooltip(Tooltip.create(Component.literal("Page " + (menu.page() + 1) + "/" + menu.pages())));
        }
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        refreshLayout();
        cancelNextRelease = false;
        customClick = false;
        var captures = menu.filtering() ? new net.minecraft.nbt.ListTag() : menu.bag().settings().getListOrEmpty("captured_entities");
        for (int index = 0; index < captures.size(); index++) {
            var capture = captures.getCompoundOrEmpty(index);
            int row = capture.getIntOr("y", 0) - menu.page() * menu.visibleRows();
            int rows = capture.getIntOr("height", 1);
            if (row >= menu.visibleRows() || row + rows <= 0) continue;
            if (isHovering(menu.storageX() + capture.getIntOr("x", 0) * 18, menu.storageY() + Math.max(0, row) * 18,
                    capture.getIntOr("width", 1) * 18 - 2, (Math.min(menu.visibleRows(), row + rows) - Math.max(0, row)) * 18 - 2,
                    event.x(), event.y())) {
                beginCustomClick(event);
                if (event.button() == 1) send("release_mob", index, 0, "");
                return true;
            }
        }
        var resources = menu.filtering() ? List.<InstalledUpgrade>of() : resources();
        for (InstalledUpgrade resource : resources)
            if (resourceBounds(resource.slot()).filter(bounds -> contains(bounds, event.x(), event.y())).isPresent()) {
                beginCustomClick(event);
                send("resource_container", resource.slot(), 0, "");
                return true;
            }
        InstalledUpgrade upgrade = menu.selected().orElse(null);
        if (upgrade != null) for (int index = 0; index < menu.bag().filterSlots(upgrade); index++) {
            if (ghostBounds(index).filter(bounds -> contains(bounds, event.x(), event.y())).isPresent()) {
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
    @Override protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        // Vanilla's image dimensions are final in 26.2; the synchronized layout can grow after opening.
        return mouseX < left || mouseY < top || mouseX >= left + contentWidth || mouseY >= top + contentHeight;
    }
    @Override protected boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
        // The compact rail has no two-pixel gutters; vanilla's expanded bounds would overlap its neighbors.
        if (x == menu.layout().upgradeSlotX() && width == 16 && height == 16)
            return mouseX >= leftPos + x && mouseX < leftPos + x + width
                    && mouseY >= topPos + y && mouseY < topPos + y + height;
        return super.isHovering(x, y, width, height, mouseX, mouseY);
    }
    private void beginCustomClick(MouseButtonEvent event) {
        // Reset vanilla's last-slot/double-click state for these non-slot controls.
        // Otherwise returning a cursor item to its previous slot can collect it again.
        super.mouseClicked(event, false);
        customClick = true;
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (isQuickCrafting && quickCraftSlots.size() >= MAX_DRAG_SLOTS) return true;
        return super.mouseDragged(event, deltaX, deltaY);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (cancelNextRelease) { cancelNextRelease = false; customClick = false; return true; }
        if (customClick) { customClick = false; return true; }
        return super.mouseReleased(event);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.hasControlDown() && event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_F) {
            if (!searchExpanded) toggleSearch();
            setFocused(search);
            search.setFocused(true);
            return true;
        }
        if (search.isFocused() && event.key() != org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) return search.keyPressed(event);
        return super.keyPressed(event);
    }
    private String selectionKey() { return menu.selectedSlot() + ":" + menu.selected().map(upgrade -> upgrade.kind().id()).orElse(""); }
    private void cancelQuickCraft() {
        if (isQuickCrafting || !quickCraftSlots.isEmpty()) cancelNextRelease = true;
        isQuickCrafting = false;
        quickCraftSlots.clear();
    }
    private void refreshLayout() {
        // Initial menu contents can arrive between a client tick and the next rendered frame.
        if (!selectionKey().equals(selectedKey)) { controlPage = 0; ghostPage = 0; fuelGhostPage = 0; auxiliaryPage = 0; rebuildWidgets(); }
        else if (!layoutKey().equals(layoutKey)) rebuildWidgets();
    }
    @Override protected void containerTick() {
        refreshLayout();
        refreshStorageView();
        if (searchDebounce > 0 && --searchDebounce == 0) flushSearch();
        if (searchButton != null) searchButton.setSelected(searchExpanded || !query.isBlank());
        sortOrderButton.setTooltip(Tooltip.create(Component.literal("Sort order: " + menu.preferences().getStringOr("sort_order", "name"))));
        settingsButton.setSelected(menu.editMode() != 0);
        settingsButton.setTooltip(Tooltip.create(Component.literal(switch (menu.editMode()) {
            case 1 -> "Prefs — Memory slots: click a storage slot to remember its item";
            case 2 -> "Prefs — No-sort slots: click a storage slot to exclude it from sorting";
            default -> "Prefs — Backpack settings";
        })));
        menu.selected().ifPresent(upgrade -> {
            var name = upgrade.stack().getHoverName();
            var heading = panelTitle(upgrade).copy().withStyle(style -> style.withColor(BackpackStyle.PANEL_TEXT).withoutShadow());
            if (upgradeHeading != null && !upgradeHeading.getMessage().equals(heading)) {
                upgradeHeading.setMessage(heading);
                upgradeHeading.setTooltip(Tooltip.create(name));
            }
            var settings = menu.bag().settings(upgrade);
            for (OptionButton option : optionButtons) {
                var optionSettings = option.action().startsWith("inception_") ? menu.preferences() : settings;
                var presentation = UpgradeControls.presentation(option.action(), upgrade.kind(), optionSettings);
                option.button().setMessage(Component.literal(presentation.label()));
                option.button().setIcon(presentation.icon()).setSelected(presentation.selected());
                optionTooltips.add(option.button(), Component.literal(UpgradeControls.help(option.action(), upgrade.kind(), optionSettings)));
            }
        });
        optionTooltips.refresh(minecraft);
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
