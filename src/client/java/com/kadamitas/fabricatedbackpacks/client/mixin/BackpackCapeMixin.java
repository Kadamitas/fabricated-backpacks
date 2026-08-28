package com.kadamitas.fabricatedbackpacks.client.mixin;

import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppress the intersecting cape only while a synchronized backpack is worn. */
@Mixin(CapeLayer.class)
abstract class BackpackCapeMixin {
    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void fabricatedBackpacks$hideCape(PoseStack poses, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                                             float limbSwing, float limbAmount, float partialTick, float age,
                                             float headYaw, float headPitch, CallbackInfo callback) {
        if (!BackpackEquipment.visual(player).isEmpty()) callback.cancel();
    }
}
