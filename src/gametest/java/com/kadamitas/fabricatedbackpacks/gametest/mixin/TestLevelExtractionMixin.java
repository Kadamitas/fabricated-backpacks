package com.kadamitas.fabricatedbackpacks.gametest.mixin;

import com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes the real extraction; never manufactures or replaces render data. */
@Mixin(value = LevelExtractor.class, remap = false)
abstract class TestLevelExtractionMixin {
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "extract", at = @At("TAIL"))
    private void fabricatedBackpacks$observeExtraction(DeltaTracker ticks, Camera camera, float partialTick, CallbackInfo callback) {
        BackpackClientGameTests.observeNativeExtraction(levelRenderState);
    }
}
