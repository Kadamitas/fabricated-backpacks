package com.kadamitas.fabricatedbackpacks.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** Native 1.21.1 text clipping and deferred, viewport-bounded screen tooltips. */
public final class ClientText {
    private ClientText() {}

    public static FormattedCharSequence clipText(Component text, Font font, int width) {
        if (font.width(text) <= width) return text.getVisualOrderText();
        Component ellipsis = Component.literal("…");
        if (width < font.width(ellipsis)) return FormattedCharSequence.EMPTY;
        return Language.getInstance().getVisualOrder(FormattedText.composite(
                font.substrByWidth(text, width - font.width(ellipsis)), ellipsis));
    }

    public static void tooltip(Component message, int mouseX, int mouseY) {
        tooltip(Minecraft.getInstance().font, message, mouseX, mouseY);
    }

    public static void tooltip(Font font, Component message, int mouseX, int mouseY) {
        int width = Math.max(1, Math.min(260, Minecraft.getInstance().getWindow().getGuiScaledWidth() - 24));
        tooltip(font, font.split(message, width), mouseX, mouseY);
    }

    public static void tooltip(Font font, List<? extends FormattedCharSequence> lines, int mouseX, int mouseY) {
        var screen = Minecraft.getInstance().screen;
        if (screen != null) screen.setTooltipForNextRenderPass(new ArrayList<>(lines));
    }

    public static void components(Font font, List<Component> messages, int mouseX, int mouseY) {
        int width = Math.max(1, Math.min(260, Minecraft.getInstance().getWindow().getGuiScaledWidth() - 24));
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (Component message : messages) lines.addAll(font.split(message, width));
        tooltip(font, lines, mouseX, mouseY);
    }
}
