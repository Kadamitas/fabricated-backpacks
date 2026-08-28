package com.kadamitas.fabricatedbackpacks.client.mixin;

import com.kadamitas.fabricatedbackpacks.client.render.BackpackRendering;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures synchronized equipment on the extraction thread, before submission. */
@Mixin(value = AvatarRenderer.class, remap = false)
abstract class AvatarBackpackStateMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
    private void fabricatedBackpacks$captureEquipment(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo callback) {
        BackpackRendering.capture(avatar, state);
    }
}
