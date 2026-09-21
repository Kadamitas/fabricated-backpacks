package com.kadamitas.fabricatedbackpacks.gametest;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;

import java.lang.reflect.Field;

/** Read-only observations of real UI state; no game classes are transformed or modified. */
final class UiInspection {
    private static final Field LEFT = field(AbstractContainerScreen.class, "leftPos");
    private static final Field TOP = field(AbstractContainerScreen.class, "topPos");
    private static final Field HOVERED = field(AbstractContainerScreen.class, "hoveredSlot");
    private static final Field TOOLTIP = field(AbstractWidget.class, "tooltip");

    private UiInspection() {}

    static ContainerView container(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?>))
            throw new AssertionError("Expected a real container screen, found " + screen);
        return new ContainerView((Integer) read(LEFT, screen), (Integer) read(TOP, screen),
                (Slot) read(HOVERED, screen));
    }

    static Tooltip tooltip(AbstractWidget widget) {
        return ((WidgetTooltipHolder) read(TOOLTIP, widget)).get();
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            if (!field.trySetAccessible()) throw new AssertionError("Cannot observe " + owner.getName() + "." + name);
            return field;
        } catch (NoSuchFieldException failure) {
            throw new AssertionError("UI observation no longer matches " + owner.getName() + "." + name, failure);
        }
    }

    private static Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException failure) {
            throw new AssertionError("Cannot observe actual UI state: " + field, failure);
        }
    }

    record ContainerView(int left, int top, Slot hoveredSlot) {}
}
