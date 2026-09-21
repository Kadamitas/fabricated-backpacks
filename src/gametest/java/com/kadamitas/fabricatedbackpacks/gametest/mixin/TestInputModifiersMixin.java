package com.kadamitas.fabricatedbackpacks.gametest.mixin;

import net.fabricmc.fabric.impl.client.gametest.TestInputImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Fabric's 26.2 test driver tracks held modifiers but constructs input events with zero flags.
 * Supply those flags on the test driver only; production input and screen code are unchanged.
 */
@Mixin(value = TestInputImpl.class, remap = false)
abstract class TestInputModifiersMixin {
    @ModifyArg(method = "pressOrReleaseKey", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/input/KeyEvent;<init>(III)V"), index = 2, require = 2)
    private static int keyboardModifiers(int original) { return original | heldModifiers(); }

    @ModifyArg(method = "pressOrReleaseKey", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/input/MouseButtonInfo;<init>(II)V"), index = 1, require = 1)
    private static int mouseModifiers(int original) { return original | heldModifiers(); }

    private static int heldModifiers() {
        int flags = 0;
        if (held(com.mojang.blaze3d.platform.InputConstants.KEY_LSHIFT, com.mojang.blaze3d.platform.InputConstants.KEY_RSHIFT)) flags |= com.mojang.blaze3d.platform.InputConstants.MOD_SHIFT;
        if (held(com.mojang.blaze3d.platform.InputConstants.KEY_LCONTROL, com.mojang.blaze3d.platform.InputConstants.KEY_RCONTROL)) flags |= com.mojang.blaze3d.platform.InputConstants.MOD_CONTROL;
        if (held(com.mojang.blaze3d.platform.InputConstants.KEY_LALT, com.mojang.blaze3d.platform.InputConstants.KEY_RALT)) flags |= com.mojang.blaze3d.platform.InputConstants.MOD_ALT;
        if (held(com.mojang.blaze3d.platform.InputConstants.KEY_LGUI, com.mojang.blaze3d.platform.InputConstants.KEY_RGUI)) flags |= com.mojang.blaze3d.platform.InputConstants.MOD_SUPER;
        return flags;
    }

    private static boolean held(int left, int right) { return TestInputImpl.isKeyDown(left) || TestInputImpl.isKeyDown(right); }
}
