package com.kadamitas.fabricatedbackpacks.gametest.mixin;

import com.kadamitas.fabricatedbackpacks.client.screen.BackpackIconButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the widget's declared help policy without changing production behavior. */
@Mixin(BackpackIconButton.class)
public interface TestIconButtonAccess {
    @Accessor("automaticTooltip") boolean fabricatedBackpacksTests$automaticTooltip();
}
