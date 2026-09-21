package com.kadamitas.fabricatedbackpacks.client.mixin;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
abstract class ConduitMiningPredictionMixin {
    @Shadow @Final private Minecraft minecraft;

    // Keep the BE until the server confirms its new lanes or final removal. Deleting it predictively
    // can discard the authoritative lane packet before the normal block-change acknowledgement arrives.
    // NeoForge performs the removal through its block-state hook after the normal break checks.
    @Inject(method = "destroyBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;onDestroyedByPlayer(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/world/level/material/FluidState;)Z"),
            cancellable = true)
    private void fabricatedBackpacks$retainConduitUntilConfirmed(BlockPos position, CallbackInfoReturnable<Boolean> result) {
        if (minecraft.level != null && minecraft.level.getBlockState(position).getBlock() instanceof ConduitBundleBlock)
            result.setReturnValue(true);
    }
}
