package com.kadamitas.fabricatedbackpacks.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.IdentityHashMap;
import java.util.Map;

/** Context help is attached only while Shift is held, without restarting the hover delay every tick. */
final class ShiftTooltips {
    private record Entry(Component help, Tooltip tooltip) {}
    private final Map<AbstractWidget, Entry> entries = new IdentityHashMap<>();
    private boolean initialized;
    private boolean visible;

    void clear() {
        entries.clear();
        initialized = false;
    }

    <T extends AbstractWidget> T add(T widget, String help) {
        return add(widget, Component.literal(help));
    }

    <T extends AbstractWidget> T add(T widget, Component help) {
        Entry current = entries.get(widget);
        if (current != null && current.help().equals(help)) return widget;
        Entry entry = new Entry(help, Tooltip.create(help));
        entries.put(widget, entry);
        if (initialized) widget.setTooltip(visible ? entry.tooltip() : null);
        return widget;
    }

    void refresh(Minecraft minecraft) {
        boolean next = minecraft != null && minecraft.hasShiftDown();
        if (initialized && visible == next) return;
        initialized = true;
        visible = next;
        entries.forEach((widget, entry) -> widget.setTooltip(visible ? entry.tooltip() : null));
    }
}
