package com.kadamitas.fabricatedbackpacks.gametest;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import java.lang.reflect.Field;

/** Read-only observations of actual UI state; no game classes are transformed or modified. */
final class UiInspection {
    private static final Field TOOLTIP = tooltipField();
    private UiInspection() {}

    static Tooltip tooltip(AbstractWidget widget) {
        try {
            return ((WidgetTooltipHolder) TOOLTIP.get(widget)).get();
        } catch (IllegalAccessException failure) {
            throw new AssertionError("Cannot observe actual widget tooltip", failure);
        }
    }

    private static Field tooltipField() {
        try {
            Field field = AbstractWidget.class.getDeclaredField("tooltip");
            if (!field.trySetAccessible()) throw new AssertionError("Cannot observe AbstractWidget.tooltip");
            return field;
        } catch (NoSuchFieldException failure) {
            throw new AssertionError("UI observation no longer matches AbstractWidget.tooltip", failure);
        }
    }
}
