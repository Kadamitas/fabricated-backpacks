package com.kadamitas.fabricatedbackpacks.client.automation;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineSideMenu;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackIconButton;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackIconButton.Icon;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackStyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ComponentRenderUtils;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Native machine-side settings: only synchronized modes, never virtual resource slots. */
public final class SteamEngineSideScreen extends AbstractContainerScreen<SteamEngineSideMenu> {
    private static final Map<ConduitKind, ItemStack> RESOURCE_ICONS = Map.of(
            ConduitKind.ITEM, Items.CHEST.getDefaultInstance(),
            ConduitKind.FLUID, Items.WATER_BUCKET.getDefaultInstance(),
            ConduitKind.ENERGY, Items.REDSTONE.getDefaultInstance());
    private final Map<Direction, ConduitFaceButton> faces = new EnumMap<>(Direction.class);
    private final Map<ConduitKind, BackpackIconButton> modes = new EnumMap<>(ConduitKind.class);

    public SteamEngineSideScreen(SteamEngineSideMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = 176; imageHeight = 140;
    }

    @Override protected void init() {
        super.init();
        faces.clear();
        modes.clear();
        for (Direction face : Direction.values()) {
            var button = new ConduitFaceButton(leftPos + 17 + face.ordinal() * 24, topPos + 25,
                    text("face", faceName(face)), face, () -> click(face.ordinal()));
            faces.put(face, addRenderableWidget(button));
        }
        for (ConduitKind kind : ConduitKind.values()) {
            modes.put(kind, addRenderableWidget(new BackpackIconButton(leftPos + 143,
                    topPos + 58 + kind.ordinal() * 26, 16, 16, modeLabel(kind), Icon.DIRECTION,
                    () -> click(10 + kind.ordinal()))));
        }
        updateControls();
    }

    private void click(int action) {
        if (minecraft.gameMode != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId, action);
    }

    @Override protected void containerTick() {
        super.containerTick();
        updateControls();
    }

    private void updateControls() {
        faces.forEach((face, button) -> button.selected(face == menu.selectedFace()));
        modes.forEach((kind, button) -> {
            button.setMessage(modeLabel(kind));
            button.setIcon(switch (menu.mode(kind, menu.selectedFace())) {
                case INPUT -> Icon.TRANSFER_DOWN;
                case OUTPUT -> Icon.TRANSFER_UP;
                case BOTH -> Icon.DIRECTION;
                case DISABLED -> Icon.STOP;
            });
        });
    }

    private Component modeLabel(ConduitKind kind) {
        return text("lane_mode", text("resource." + kind.name().toLowerCase(Locale.ROOT)),
                faceName(menu.selectedFace()),
                text("engine_mode." + menu.mode(kind, menu.selectedFace()).name().toLowerCase(Locale.ROOT)));
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
    @Override protected void renderBg(GuiGraphics g, float delta, int mouseX, int mouseY) {
        BackpackStyle.frame(g, leftPos, topPos, imageWidth, imageHeight, BackpackStyle.Surface.BODY);
        BackpackStyle.frame(g, leftPos + 3, topPos + 3, imageWidth - 6, 13, BackpackStyle.Surface.TITLE);
        if (isHovering(8, 3, imageWidth - 16, 13, mouseX, mouseY))
            setWrappedTooltip(g, title, mouseX, mouseY);
        for (ConduitKind kind : ConduitKind.values()) {
            int y = 54 + kind.ordinal() * 26;
            BackpackStyle.frame(g, leftPos + 9, topPos + y, imageWidth - 18, 24, BackpackStyle.Surface.PANEL);
            g.renderFakeItem(RESOURCE_ICONS.get(kind), leftPos + 15, topPos + y + 4);
            if (isHovering(12, y + 2, 125, 20, mouseX, mouseY))
                setWrappedTooltip(g, modeLabel(kind), mouseX, mouseY);
        }
    }

    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        int titleWidth = imageWidth - 16;
        var line = font.width(title) > titleWidth
                ? com.kadamitas.fabricatedbackpacks.client.screen.ClientText.clipText(title, font, titleWidth) : title.getVisualOrderText();
        FormattedCharSequence visible = sink -> line.accept((index, style, codePoint) ->
                sink.accept(index, style, codePoint));
        // Preserve the full title for native narration and hover, clipping only its painted form.
        g.enableScissor(leftPos + 8, topPos + 4, leftPos + 8 + titleWidth, topPos + 16);
        try {
            g.drawString(font, visible, 8, 6, BackpackStyle.TITLE_TEXT, false);
        } finally {
            g.disableScissor();
        }
    }

    private static Component text(String key, Object... arguments) { return SteamEngineScreen.text(key, arguments); }
    private static Component faceName(Direction face) { return text("face." + face.getName()); }

    private void setWrappedTooltip(GuiGraphics graphics, Component message, int mouseX, int mouseY) {
        int maxWidth = Math.max(1, Math.min(260, width - 24));
        com.kadamitas.fabricatedbackpacks.client.screen.ClientText.tooltip(font, font.split(message, maxWidth), mouseX, mouseY);
    }
}
