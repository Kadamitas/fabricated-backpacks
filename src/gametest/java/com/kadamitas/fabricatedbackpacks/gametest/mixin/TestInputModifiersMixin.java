package com.kadamitas.fabricatedbackpacks.gametest.mixin;

import net.fabricmc.fabric.impl.client.gametest.TestInputImpl;
import org.lwjgl.glfw.GLFW;
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
        if (held(GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT)) flags |= GLFW.GLFW_MOD_SHIFT;
        if (held(GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL)) flags |= GLFW.GLFW_MOD_CONTROL;
        if (held(GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT)) flags |= GLFW.GLFW_MOD_ALT;
        if (held(GLFW.GLFW_KEY_LEFT_SUPER, GLFW.GLFW_KEY_RIGHT_SUPER)) flags |= GLFW.GLFW_MOD_SUPER;
        return flags;
    }

    private static boolean held(int left, int right) { return TestInputImpl.isKeyDown(left) || TestInputImpl.isKeyDown(right); }
}
