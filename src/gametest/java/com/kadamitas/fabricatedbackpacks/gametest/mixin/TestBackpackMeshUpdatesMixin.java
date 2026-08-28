package com.kadamitas.fabricatedbackpacks.gametest.mixin;

import com.kadamitas.fabricatedbackpacks.gametest.PlacedAppearanceAcceptance;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes completed client mesh invalidation without changing rendering or world state. */
@Mixin(ClientLevel.class)
abstract class TestBackpackMeshUpdatesMixin {
    @Inject(method = "sendBlockUpdated", at = @At("TAIL"))
    private void observeMeshInvalidation(BlockPos position, BlockState previous, BlockState next, int flags, CallbackInfo info) {
        PlacedAppearanceAcceptance.observeMeshInvalidation((ClientLevel) (Object) this, position, flags);
    }
}
