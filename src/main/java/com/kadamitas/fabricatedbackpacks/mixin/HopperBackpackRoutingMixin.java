package com.kadamitas.fabricatedbackpacks.mixin;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only hopper routing changes: Fabric's existing fallback then uses the backpack's transactional sided API. */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBackpackRoutingMixin {
    @Inject(method = {"getAttachedContainer", "getSourceContainer"}, at = @At("RETURN"), cancellable = true)
    private static void fabricatedBackpacks$useSidedStorage(CallbackInfoReturnable<Container> result) {
        if (result.getReturnValue() instanceof BackpackBlockEntity) result.setReturnValue(null);
    }
}
