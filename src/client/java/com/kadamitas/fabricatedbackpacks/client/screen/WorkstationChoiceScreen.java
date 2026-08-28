package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Searches authoritative result previews; selection sends only a recipe identity, never an item. */
public final class WorkstationChoiceScreen extends Screen {
    private record Choice(Identifier id, ItemStack result) {}
    private final Screen previous;
    private final int containerId;
    private final List<ResultButton> cells = new ArrayList<>();
    private List<Choice> choices = List.of();
    private CompoundTag seen = new CompoundTag();
    private String query = "";
    private int page, pages = 1, columns, rows, left, top, panelWidth, panelHeight;
    private EditBox search;
    private Button backward, forward;

    WorkstationChoiceScreen(Screen previous, int containerId) {
        super(Component.literal("Choose a recipe result"));
        this.previous = previous;
        this.containerId = containerId;
    }

    @Override protected void init() {
        columns = Math.clamp((width - 40) / 24, 1, 9);
        rows = Math.clamp((height - 100) / 24, 1, 5);
        panelWidth = columns * 24 + 20;
        panelHeight = rows * 24 + 88;
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        cells.clear();
        addRenderableWidget(Button.builder(Component.literal("Back"), ignored -> onClose()).bounds(left + panelWidth - 48, top + 6, 40, 16).build());
        search = addRenderableWidget(new EditBox(font, left + 10, top + 28, panelWidth - 20, 18, Component.literal("Search recipe results")));
        search.setMaxLength(120);
        search.setHint(Component.literal("Search names or recipe IDs"));
        search.setValue(query);
        search.setResponder(value -> { query = value; page = 0; refresh(); });
        backward = addRenderableWidget(Button.builder(Component.literal("<"), ignored -> { page--; refresh(); })
                .bounds(left + 10, top + panelHeight - 26, 28, 16).build());
        forward = addRenderableWidget(Button.builder(Component.literal(">"), ignored -> { page++; refresh(); })
                .bounds(left + panelWidth - 38, top + panelHeight - 26, 28, 16).build());
        updateChoices();
    }

    private boolean valid() {
        return minecraft.player != null && minecraft.level != null && minecraft.player.isAlive()
                && minecraft.player.containerMenu.containerId == containerId;
    }

    @Override public void tick() {
        if (!valid()) { minecraft.gui.setScreen(null); return; }
        if (!seen.equals(WorkstationControls.currentState(containerId))) updateChoices();
    }

    private void updateChoices() {
        seen = WorkstationControls.currentState(containerId);
        var results = seen.getListOrEmpty("choice_results");
        String[] identifiers = seen.getStringOr("choices", "").split(",");
        var ops = RegistryOps.create(NbtOps.INSTANCE, minecraft.level.registryAccess());
        List<Choice> loaded = new ArrayList<>();
        for (int index = 0; index < Math.min(1024, Math.min(identifiers.length, results.size())); index++) {
            Identifier id = Identifier.tryParse(identifiers[index]);
            ItemStack result = ItemStack.OPTIONAL_CODEC.parse(ops, results.get(index)).result().orElse(ItemStack.EMPTY);
            if (id != null && !result.isEmpty()) loaded.add(new Choice(id, result));
        }
        choices = List.copyOf(loaded);
        refresh();
    }

    private void refresh() {
        if (backward == null) return;
        cells.forEach(this::removeWidget);
        cells.clear();
        String needle = query.strip().toLowerCase(Locale.ROOT);
        List<Choice> filtered = choices.stream().filter(choice -> needle.isEmpty()
                || choice.id().toString().contains(needle) || choice.result().getHoverName().getString().toLowerCase(Locale.ROOT).contains(needle)).toList();
        int pageSize = columns * rows;
        pages = Math.max(1, Math.ceilDiv(filtered.size(), pageSize));
        page = Math.clamp(page, 0, pages - 1);
        for (int index = page * pageSize; index < Math.min(filtered.size(), (page + 1) * pageSize); index++) {
            int cell = index % pageSize;
            cells.add(addRenderableWidget(new ResultButton(left + 12 + cell % columns * 24, top + 52 + cell / columns * 24, filtered.get(index))));
        }
        backward.active = page > 0;
        forward.active = page + 1 < pages;
    }

    @Override public void onClose() { minecraft.gui.setScreen(valid() ? previous : null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean isInGameUi() { return true; }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xd0111c24);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xff24343c);
        graphics.outline(left, top, panelWidth, panelHeight, 0xffb49359);
        graphics.text(font, Component.literal("Recipe results"), left + 10, top + 10, 0xffe8c89a);
        graphics.centeredText(font, (page + 1) + " / " + pages, left + panelWidth / 2, top + panelHeight - 22, 0xffe3e1d5);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private final class ResultButton extends AbstractButton {
        private final Choice choice;
        ResultButton(int x, int y, Choice choice) { super(x, y, 20, 20, choice.result().getHoverName()); this.choice = choice; }
        @Override public void onPress(InputWithModifiers input) {
            if (valid()) ClientPlayNetworking.send(new MenuAction(containerId, "workstation_choice", 0, 0, choice.id().toString()));
            onClose();
        }
        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), isHoveredOrFocused() ? 0xff708b89 : 0xff17262d);
            graphics.fakeItem(choice.result(), getX() + 2, getY() + 2);
            graphics.itemDecorations(font, choice.result(), getX() + 2, getY() + 2);
            if (isHoveredOrFocused()) graphics.setComponentTooltipForNextFrame(font,
                    List.of(choice.result().getHoverName(), Component.literal(choice.id().toString())), mouseX, mouseY);
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }
}
