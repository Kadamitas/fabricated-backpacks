package com.kadamitas.fabricatedbackpacks.mixin;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMining;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
abstract class ConduitMiningMixin {
    @Shadow protected ServerPlayer player;

    // Fabric invokes every BEFORE callback at playerWillDestroy, before this distinct later call.
    @Inject(method = "destroyBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"), cancellable = true)
    private void fabricatedBackpacks$mineOneConduit(BlockPos position, CallbackInfoReturnable<Boolean> result) {
        ConduitMining.Result mined = ConduitMining.complete(player, position);
        if (mined != ConduitMining.Result.PASS) result.setReturnValue(mined == ConduitMining.Result.REMOVED);
    }
}
