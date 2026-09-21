package com.kadamitas.fabricatedbackpacks.mixin;

import com.kadamitas.fabricatedbackpacks.platform.NativeEvents.ServerBlockEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BlockEntity.class)
abstract class NativeBlockEntityLifecycleMixin {
    @Inject(method = "setLevel", at = @At("TAIL"))
    private void fabricatedBackpacks$load(Level level, CallbackInfo callback) {
        if (level instanceof ServerLevel server) ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.fire(c -> c.accept((BlockEntity)(Object)this, server));
    }
    @Inject(method = "setRemoved", at = @At("HEAD"))
    private void fabricatedBackpacks$unload(CallbackInfo callback) {
        BlockEntity entity = (BlockEntity)(Object)this;
        if (entity.getLevel() instanceof ServerLevel server) ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.fire(c -> c.accept(entity, server));
    }
}
