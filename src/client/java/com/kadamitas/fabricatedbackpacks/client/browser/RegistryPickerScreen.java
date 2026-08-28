package com.kadamitas.fabricatedbackpacks.client.browser;

import com.kadamitas.fabricatedbackpacks.browser.BrowserQuery;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackIconButton;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackIconButton.Icon;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackStyle;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A registry picker only: selecting an entry never grants an item or transfers a resource. */
public final class RegistryPickerScreen extends Screen {
    enum Kind { ITEM, FLUID }
    private final Screen previous;
    private final Kind kind;
    private final BooleanSupplier live;
    private final Consumer<Identifier> selected;
    private final List<EntryButton> entries = new ArrayList<>();
    private EditBox search;
    private BackpackIconButton previousPage, nextPage;
    private List<Identifier> matches = List.of();
    private String query = "";
    private int left, top, panelWidth, panelHeight, columns, rows, gridX, gridY, page;
    private Object seenIndex;
    private long seenVersion = -1;
    private boolean invalidQuery;

    RegistryPickerScreen(Screen previous, Kind kind, BooleanSupplier live, Consumer<Identifier> selected) {
        super(text(kind == Kind.ITEM ? "picker.item" : "picker.fluid"));
        this.previous = Objects.requireNonNull(previous);
        this.kind = Objects.requireNonNull(kind);
        this.live = Objects.requireNonNull(live);
        this.selected = Objects.requireNonNull(selected);
    }

    void beginIndex(Minecraft client) {
        if (kind == Kind.ITEM) RecipeBrowserClient.index().begin(client);
        else RecipeBrowserClient.fluids().begin(client);
    }

    @Override protected void init() {
        panelWidth = Math.min(284, width - 12);
        panelHeight = Math.min(242, height - 12);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        columns = Math.max(1, (panelWidth - 16) / 18);
        rows = Math.max(1, (panelHeight - 82) / 18);
        gridX = left + (panelWidth - columns * 18) / 2;
        gridY = top + 52;
        entries.clear();
        addRenderableWidget(new BackpackIconButton(left + panelWidth - 24, top + 5, 16, 16,
                text("picker.back"), Icon.PREVIOUS, this::onClose));
        Component help = Component.translatable("browser.fabricated_backpacks.search_hint");
        search = addRenderableWidget(new EditBox(font, left + 8, top + 27, panelWidth - 16, 18, help));
        search.setMaxLength(BrowserQuery.MAX_LENGTH);
        search.setHint(text(kind == Kind.ITEM ? "picker.search_items" : "picker.search_fluids"));
        search.setTooltip(Tooltip.create(help));
        search.setValue(query);
        search.setResponder(value -> { query = value; page = 0; refreshEntries(); });
        previousPage = addRenderableWidget(new BackpackIconButton(left + 8, top + panelHeight - 24, 16, 16,
                text("picker.previous"), Icon.PREVIOUS, () -> { page--; refreshEntries(); }));
        nextPage = addRenderableWidget(new BackpackIconButton(left + panelWidth - 24, top + panelHeight - 24, 16, 16,
                text("picker.next"), Icon.NEXT, () -> { page++; refreshEntries(); }));
        refreshEntries();
        setFocused(search);
        search.setFocused(true);
    }

    @Override public void tick() {
        if (!validContext()) { minecraft.gui.setScreen(null); return; }
        beginIndex(minecraft);
        Object index = kind == Kind.ITEM ? RecipeBrowserClient.index() : RecipeBrowserClient.fluids();
        long version = kind == Kind.ITEM ? RecipeBrowserClient.index().version() : RecipeBrowserClient.fluids().version();
        if (index != seenIndex || version != seenVersion) {
            seenIndex = index;
            seenVersion = version;
            refreshEntries();
        }
    }

    private boolean validContext() {
        return minecraft != null && minecraft.player != null && minecraft.level != null && minecraft.getConnection() != null
                && minecraft.player.isAlive() && live.getAsBoolean();
    }

    @Override public void onClose() { minecraft.gui.setScreen(validContext() ? previous : null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }

    private void choose(Identifier id) {
        if (validContext()) selected.accept(id);
        onClose();
    }

    private void refreshEntries() {
        if (search == null || previousPage == null || nextPage == null) return;
        for (EntryButton entry : entries) removeWidget(entry);
        entries.clear();
        invalidQuery = false;
        try {
            matches = kind == Kind.ITEM ? RecipeBrowserClient.index().search(query).stream().map(BrowserClientIndex.BrowserItem::id).toList()
                    : RecipeBrowserClient.fluids().search(query).stream().map(BrowserFluidIndex.Entry::id).toList();
        } catch (IllegalArgumentException invalid) {
            matches = List.of();
            invalidQuery = true;
        }
        int perPage = columns * rows;
        int pages = Math.ceilDiv(matches.size(), perPage);
        page = Math.clamp(page, 0, Math.max(0, pages - 1));
        int start = page * perPage;
        for (int index = start; index < Math.min(matches.size(), start + perPage); index++) {
            int cell = index - start;
            entries.add(addRenderableWidget(new EntryButton(gridX + cell % columns * 18,
                    gridY + cell / columns * 18, matches.get(index))));
        }
        previousPage.active = page > 0;
        nextPage.active = page + 1 < pages;
    }

    @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX >= gridX && mouseX < gridX + columns * 18 && mouseY >= gridY
                && mouseY < gridY + rows * 18 && vertical != 0) {
            page += vertical > 0 ? -1 : 1;
            refreshEntries();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (event.hasControlDown() && event.key() == GLFW.GLFW_KEY_F) {
            setFocused(search); search.setFocused(true); return true;
        }
        return super.keyPressed(event);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xC011171D);
        BackpackStyle.frame(graphics, left, top, panelWidth, panelHeight, BackpackStyle.Surface.BODY);
        BackpackStyle.frame(graphics, left + 3, top + 3, panelWidth - 6, 20, BackpackStyle.Surface.TITLE);
        graphics.text(font, title, left + 8, top + 9, BackpackStyle.TITLE_TEXT, false);
        int pages = Math.ceilDiv(matches.size(), columns * rows);
        String position = (pages == 0 ? 0 : page + 1) + " / " + pages;
        graphics.text(font, position, left + (panelWidth - font.width(position)) / 2,
                top + panelHeight - 20, BackpackStyle.TEXT, false);
        if (matches.isEmpty()) {
            boolean building = kind == Kind.ITEM ? RecipeBrowserClient.index().itemsBuilding() : RecipeBrowserClient.fluids().building();
            Component message = invalidQuery ? Component.translatable("browser.fabricated_backpacks.query_too_complex")
                    : text(building ? "picker.loading" : "picker.empty");
            graphics.textWithWordWrap(font, message, left + 10, gridY + 5, panelWidth - 20, BackpackStyle.TEXT, false);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private final class EntryButton extends AbstractButton {
        private final Identifier id;
        private final ItemStack item;
        private final List<Component> tooltip;

        EntryButton(int x, int y, Identifier id) {
            super(x, y, 18, 18, kind == Kind.ITEM ? RecipeBrowserClient.index().item(id).getHoverName() : FluidPresentation.name(id));
            this.id = id;
            item = kind == Kind.ITEM ? RecipeBrowserClient.index().item(id) : ItemStack.EMPTY;
            if (kind == Kind.FLUID) tooltip = FluidPresentation.tooltip(id);
            else {
                var lines = new ArrayList<>(Screen.getTooltipFromItem(minecraft, item));
                if (lines.stream().noneMatch(line -> line.getString().equals(id.toString())))
                    lines.add(Component.literal(id.toString()).withStyle(ChatFormatting.DARK_GRAY));
                tooltip = List.copyOf(lines);
            }
            Component fullTooltip = Component.empty();
            for (int line = 0; line < tooltip.size(); line++) {
                if (line != 0) fullTooltip = fullTooltip.copy().append("\n");
                fullTooltip = fullTooltip.copy().append(tooltip.get(line));
            }
            setTooltip(Tooltip.create(fullTooltip));
        }

        @Override public void onPress(InputWithModifiers input) { choose(id); }

        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            BackpackStyle.slot(graphics, getX() + 1, getY() + 1, true);
            graphics.enableScissor(getX() + 1, getY() + 1, getRight() - 1, getBottom() - 1);
            try {
                if (kind == Kind.ITEM) graphics.fakeItem(item, getX() + 1, getY() + 1);
                else FluidPresentation.draw(graphics, id, getX() + 1, getY() + 1);
            } finally { graphics.disableScissor(); }
            if (isHoveredOrFocused()) graphics.outline(getX(), getY(), 18, 18, 0xFFFFD375);
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput narration) { defaultButtonNarrationText(narration); }
    }

    private static Component text(String key, Object... arguments) {
        return Component.translatable("automation.fabricated_backpacks." + key, arguments);
    }
}
