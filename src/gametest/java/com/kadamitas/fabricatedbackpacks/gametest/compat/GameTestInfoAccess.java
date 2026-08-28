package com.kadamitas.fabricatedbackpacks.gametest.compat;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Test fixture access only; no production classes or game rules are changed. */
@Mixin(GameTestHelper.class)
public interface GameTestInfoAccess {
    @Accessor("testInfo") GameTestInfo fabricatedBackpacks$testInfo();
}
