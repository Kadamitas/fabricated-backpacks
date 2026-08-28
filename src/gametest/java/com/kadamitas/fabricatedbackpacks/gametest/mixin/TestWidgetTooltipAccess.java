package com.kadamitas.fabricatedbackpacks.gametest.mixin;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the tooltip attached to the actual widget; this accessor is absent from production. */
@Mixin(AbstractWidget.class)
public interface TestWidgetTooltipAccess {
    @Accessor("tooltip") WidgetTooltipHolder fabricatedBackpacksTests$tooltip();
}
