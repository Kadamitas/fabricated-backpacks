package com.kadamitas.fabricatedbackpacks.client.automation;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMenu;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilter;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilterAction;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilterMode;
import com.kadamitas.fabricatedbackpacks.client.browser.FluidPresentation;
import com.kadamitas.fabricatedbackpacks.client.browser.RecipeBrowserClient;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackIconButton;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackIconButton.Icon;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackStyle;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Settings for the physically clicked interface only; filter ghosts never hold actual resources. */
public final class ConduitScreen extends AbstractContainerScreen<ConduitMenu> {
    public record FilterTarget(ConduitKind kind, int slot, ScreenRectangle bounds) {}
    private static final int MAIN_WIDTH = 176, FILTER_WIDTH = 76, FILTER_HEIGHT = 90;
    private final Map<ConduitKind, BackpackIconButton> modes = new EnumMap<>(ConduitKind.class);
    private final Map<ConduitKind, BackpackIconButton> redstone = new EnumMap<>(ConduitKind.class);
    private final Map<ConduitKind, BackpackIconButton> filters = new EnumMap<>(ConduitKind.class);
    private final List<GhostButton> ghosts = new ArrayList<>();
    private ConduitKind selectedFilter;
    private BackpackIconButton filterMode;

    public ConduitScreen(ConduitMenu menu, Inventory inventory, Component title) { super(menu, inventory, title, 176, 140); }
    @Override protected void init() {
        super.init();
        leftPos = (width - MAIN_WIDTH - (selectedFilter == null ? 0 : FILTER_WIDTH - 2)) / 2;
        modes.clear(); redstone.clear(); filters.clear(); ghosts.clear(); filterMode = null;
        for (ConduitKind kind : ConduitKind.values()) {
            int y = topPos + 58 + kind.ordinal() * 26;
            modes.put(kind, addRenderableWidget(new BackpackIconButton(leftPos + 111, y, 16, 16,
                    modeLabel(kind), Icon.DIRECTION, () -> click(10 + kind.ordinal()))));
            redstone.put(kind, addRenderableWidget(new BackpackIconButton(leftPos + 143, y, 16, 16,
                    redstoneLabel(kind), Icon.POWER, () -> click(20 + kind.ordinal()))));
            if (kind != ConduitKind.ENERGY) filters.put(kind, addRenderableWidget(new BackpackIconButton(leftPos + 79, y, 16, 16,
                    text("filter.open", kindName(kind), faceName(menu.selectedFace())), Icon.FILTER, () -> {
                        selectedFilter = selectedFilter == kind ? null : kind;
                        rebuildWidgets();
                    })));
        }
        if (selectedFilter != null) {
            filterMode = addRenderableWidget(new BackpackIconButton(filterX() + 48, filterY() + 7, 16, 16,
                    filterModeLabel(), Icon.POWER, this::cycleFilterMode));
            for (int slot = 0; slot < ConduitFilter.SLOT_COUNT; slot++)
                ghosts.add(addRenderableWidget(new GhostButton(filterX() + 11 + slot % 3 * 18,
                        filterY() + 29 + slot / 3 * 18, slot)));
        }
        updateControls();
    }
    private void click(int action) {
        if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, action);
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (selectedFilter != null && !menu.installed(selectedFilter)) {
            selectedFilter = null;
            rebuildWidgets();
        }
        updateControls();
    }
    private void updateControls() {
        for (ConduitKind kind : ConduitKind.values()) {
            var mode = modes.get(kind); var red = redstone.get(kind);
            if (mode == null || red == null) continue;
            mode.active = red.active = menu.installed(kind);
            mode.setMessage(modeLabel(kind)); red.setMessage(redstoneLabel(kind));
            mode.setIcon(switch (menu.mode(kind, menu.selectedFace())) {
                case EXTRACT -> Icon.TRANSFER_UP; case INSERT -> Icon.TRANSFER_DOWN;
                case BOTH -> Icon.DIRECTION; case DISABLED -> Icon.STOP;
            });
            red.setIcon(switch (menu.redstone(kind, menu.selectedFace())) {
                case ALWAYS -> Icon.REPEAT; case HIGH -> Icon.POWER; case LOW -> Icon.STOP;
            });
            var filter = filters.get(kind);
            if (filter != null) {
                filter.active = menu.installed(kind);
                filter.setSelected(kind == selectedFilter);
                filter.setMessage(text("filter.open", kindName(kind), faceName(menu.selectedFace())));
            }
        }
        if (filterMode != null && selectedFilter != null) {
            filterMode.active = menu.installed(selectedFilter);
            filterMode.setMessage(filterModeLabel());
            filterMode.setIcon(switch (menu.filter(selectedFilter).mode()) {
                case OFF -> Icon.POWER; case ALLOW -> Icon.FILTER_ALLOW; case BLOCK -> Icon.FILTER_BLOCK;
            });
            filterMode.setSelected(menu.filter(selectedFilter).mode() != ConduitFilterMode.OFF);
            filterMode.setTooltip(Tooltip.create(filterModeLabel().copy().append("\n").append(text(
                    "filter.help." + menu.filter(selectedFilter).mode().name().toLowerCase(Locale.ROOT)))));
            ghosts.forEach(GhostButton::refresh);
        }
    }

    public Optional<ConduitKind> selectedFilterKind() { return Optional.ofNullable(selectedFilter); }

    public Optional<ScreenRectangle> filterPanelBounds() {
        return panelVisible() ? Optional.of(new ScreenRectangle(filterX(), filterY(), FILTER_WIDTH, FILTER_HEIGHT)) : Optional.empty();
    }

    public List<FilterTarget> filterTargets() {
        return panelVisible() ? ghosts.stream().map(ghost -> new FilterTarget(selectedFilter, ghost.slot,
                new ScreenRectangle(ghost.getX(), ghost.getY(), ghost.getWidth(), ghost.getHeight()))).toList() : List.of();
    }

    /** Optional ingredient-browser integrations submit only an ID; the server owns the mutation. */
    public boolean acceptItem(int slot, Identifier id) {
        if (id == null || selectedFilter != ConduitKind.ITEM || !validContext()) return false;
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null || item == Items.AIR || !item.isEnabled(minecraft.level.enabledFeatures())) return false;
        return send(ConduitFilterAction.Operation.SET_ENTRY, slot, Optional.of(id));
    }

    public boolean acceptFluid(int slot, Identifier id) {
        if (id == null || selectedFilter != ConduitKind.FLUID || !validContext()) return false;
        return FluidPresentation.canonical(id).map(canonical ->
                send(ConduitFilterAction.Operation.SET_ENTRY, slot, Optional.of(canonical))).orElse(false);
    }

    private boolean validContext() {
        return minecraft != null && minecraft.player != null && minecraft.level != null && minecraft.getConnection() != null
                && minecraft.player.isAlive() && minecraft.player.containerMenu == menu;
    }

    private boolean panelVisible() {
        return selectedFilter != null && validContext() && minecraft.gui.screen() == this && menu.installed(selectedFilter);
    }

    private boolean send(ConduitFilterAction.Operation operation, int index, Optional<Identifier> resource) {
        if (!validContext() || selectedFilter == null || selectedFilter == ConduitKind.ENERGY || !menu.installed(selectedFilter)
                || !ClientPlayNetworking.canSend(ConduitFilterAction.TYPE)) return false;
        int bound = operation == ConduitFilterAction.Operation.SET_MODE ? ConduitFilterMode.values().length : ConduitFilter.SLOT_COUNT;
        if (index < 0 || index >= bound) return false;
        ClientPlayNetworking.send(new ConduitFilterAction(menu.containerId, selectedFilter, operation, index, resource));
        return true;
    }

    private void cycleFilterMode() {
        if (selectedFilter != null) send(ConduitFilterAction.Operation.SET_MODE,
                (menu.filter(selectedFilter).mode().ordinal() + 1) % ConduitFilterMode.values().length, Optional.empty());
    }

    private void openPicker(int slot) {
        if (selectedFilter == null || !validContext()) return;
        ConduitKind kind = selectedFilter;
        if (kind == ConduitKind.ITEM) RecipeBrowserClient.openItemPicker(this,
                () -> validContext() && selectedFilter == kind && menu.installed(kind), id -> acceptItem(slot, id));
        else if (kind == ConduitKind.FLUID) RecipeBrowserClient.openFluidPicker(this,
                () -> validContext() && selectedFilter == kind && menu.installed(kind), id -> acceptFluid(slot, id));
    }

    private int filterX() { return leftPos + MAIN_WIDTH - 2; }
    private int filterY() { return topPos + 44; }

    @Override protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top) {
        boolean overFilter = selectedFilter != null && mouseX >= filterX() && mouseX < filterX() + FILTER_WIDTH
                && mouseY >= filterY() && mouseY < filterY() + FILTER_HEIGHT;
        return !overFilter && super.hasClickedOutside(mouseX, mouseY, left, top);
    }

    private Component filterModeLabel() {
        return text("filter.mode", kindName(selectedFilter), faceName(menu.selectedFace()),
                text("filter.mode." + menu.filter(selectedFilter).mode().name().toLowerCase(Locale.ROOT)));
    }
    private Component modeLabel(ConduitKind kind) {
        return text("lane_mode", kindName(kind), faceName(menu.selectedFace()),
                text("mode." + menu.mode(kind, menu.selectedFace()).name().toLowerCase(Locale.ROOT)));
    }
    private Component redstoneLabel(ConduitKind kind) {
        return text("lane_redstone", kindName(kind), faceName(menu.selectedFace()),
                text("redstone." + menu.redstone(kind, menu.selectedFace()).name().toLowerCase(Locale.ROOT)));
    }
    @Override public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);
        BackpackStyle.frame(g, leftPos, topPos, MAIN_WIDTH, imageHeight, BackpackStyle.Surface.BODY);
        BackpackStyle.frame(g, leftPos + 3, topPos + 3, MAIN_WIDTH - 6, 13, BackpackStyle.Surface.TITLE);
        // A descriptive badge, not a button: another face requires another physical interface hit.
        BackpackStyle.frame(g, leftPos + 78, topPos + 25, 20, 20, BackpackStyle.Surface.PANEL);
        ConduitFaceButton.paintGlyph(g, leftPos + 78, topPos + 25, menu.selectedFace());
        if (isHovering(78, 25, 20, 20, mouseX, mouseY))
            setWrappedTooltip(g, text("face", faceName(menu.selectedFace())), mouseX, mouseY);
        for (ConduitKind kind : ConduitKind.values()) {
            int y = 54 + kind.ordinal() * 26;
            BackpackStyle.frame(g, leftPos + 9, topPos + y, MAIN_WIDTH - 18, 24, BackpackStyle.Surface.PANEL);
            g.fakeItem(AutomationRegistry.conduit(kind).getDefaultInstance(), leftPos + 15, topPos + y + 4);
            String count = menu.installed(kind) ? Integer.toString(menu.networkSize(kind)) : "-";
            int color = menu.oversized(kind) ? 0xFF9B251D : BackpackStyle.PANEL_TEXT;
            g.text(font, count, leftPos + 39, topPos + y + 8, color, false);
            if (isHovering(12, y + 2, kind == ConduitKind.ENERGY ? 94 : 60, 20, mouseX, mouseY)) {
                Component label = !menu.installed(kind) ? text("lane_mode", kindName(kind), faceName(menu.selectedFace()), text("absent"))
                        : menu.oversized(kind) ? text("oversized", kindName(kind))
                        : text("network", kindName(kind), menu.networkSize(kind));
                setWrappedTooltip(g, label, mouseX, mouseY);
            }
        }
        if (selectedFilter != null) {
            BackpackStyle.frame(g, filterX(), filterY(), FILTER_WIDTH, FILTER_HEIGHT, BackpackStyle.Surface.PANEL);
            BackpackStyle.frame(g, filterX() + 3, filterY() + 3, FILTER_WIDTH - 6, 20, BackpackStyle.Surface.TITLE);
            g.fakeItem(AutomationRegistry.conduit(selectedFilter).getDefaultInstance(), filterX() + 11, filterY() + 6);
        }
        if (isHovering(3, 3, MAIN_WIDTH - 6, 13, mouseX, mouseY)) setWrappedTooltip(g, text("conduit_help"), mouseX, mouseY);
    }
    @Override protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(font, title, 8, 6, BackpackStyle.TITLE_TEXT, false);
    }

    private final class GhostButton extends AbstractButton {
        private final int slot;

        GhostButton(int x, int y, int slot) {
            super(x, y, 16, 16, Component.empty());
            this.slot = slot;
            refresh();
        }

        void refresh() {
            if (selectedFilter == null) return;
            active = menu.installed(selectedFilter);
            Component value = menu.filter(selectedFilter).entry(slot).map(id -> selectedFilter == ConduitKind.ITEM
                    ? BuiltInRegistries.ITEM.getOptional(id).map(Item::getDefaultInstance).map(ItemStack::getHoverName)
                    .orElseGet(() -> Component.literal(id.toString())) : FluidPresentation.name(id)).orElseGet(() -> text("filter.empty"));
            Component label = text("filter.slot", kindName(selectedFilter), faceName(menu.selectedFace()), slot + 1, value);
            setMessage(label);
            var help = label.copy().append("\n").append(text("filter.ghost_help"));
            menu.filter(selectedFilter).entry(slot).ifPresent(id -> help.append("\n").append(id.toString()));
            setTooltip(Tooltip.create(help));
        }

        @Override protected boolean isValidClickButton(MouseButtonInfo button) {
            return button.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT || button.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        }

        @Override public void onPress(InputWithModifiers input) {
            if (input instanceof MouseButtonEvent mouse && mouse.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                send(ConduitFilterAction.Operation.CLEAR_ENTRY, slot, Optional.empty());
            else openPicker(slot);
        }

        @Override public boolean keyPressed(KeyEvent event) {
            if (event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                send(ConduitFilterAction.Operation.CLEAR_ENTRY, slot, Optional.empty());
                return true;
            }
            return super.keyPressed(event);
        }

        @Override protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
            BackpackStyle.slot(g, getX(), getY(), true);
            var id = selectedFilter == null ? Optional.<Identifier>empty() : menu.filter(selectedFilter).entry(slot);
            g.enableScissor(getX(), getY(), getRight(), getBottom());
            try {
                if (id.isPresent()) {
                    if (selectedFilter == ConduitKind.FLUID) FluidPresentation.draw(g, id.get(), getX(), getY());
                    else g.fakeItem(BuiltInRegistries.ITEM.getOptional(id.get()).map(Item::getDefaultInstance)
                            .orElseGet(Items.BARRIER::getDefaultInstance), getX(), getY());
                } else {
                    g.fill(getX() + 7, getY() + 4, getX() + 9, getY() + 12, 0xFFC9B990);
                    g.fill(getX() + 4, getY() + 7, getX() + 12, getY() + 9, 0xFFC9B990);
                }
            } finally { g.disableScissor(); }
            if (isHoveredOrFocused()) g.outline(getX() - 1, getY() - 1, 18, 18, 0xFFFFD375);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput narration) { defaultButtonNarrationText(narration); }
    }
    private static Component text(String key, Object... arguments) { return SteamEngineScreen.text(key, arguments); }
    private static Component kindName(ConduitKind kind) {
        return Component.translatable("item.fabricated_backpacks." + kind.name().toLowerCase(Locale.ROOT) + "_conduit");
    }
    private static Component faceName(Direction face) { return text("face." + face.getName()); }

    private void setWrappedTooltip(GuiGraphicsExtractor graphics, Component message, int mouseX, int mouseY) {
        int maxWidth = Math.max(1, Math.min(260, width - 24));
        graphics.setTooltipForNextFrame(font, font.split(message, maxWidth), mouseX, mouseY);
    }
}
