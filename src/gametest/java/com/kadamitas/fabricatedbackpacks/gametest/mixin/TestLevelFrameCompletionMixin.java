package com.kadamitas.fabricatedbackpacks.gametest.mixin;

import com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Returns only after Forge's native level frame graph actually executes. */
@Mixin(value = LevelRenderer.class, remap = false)
abstract class TestLevelFrameCompletionMixin {
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "render", at = @At("TAIL"))
    private void fabricatedBackpacks$observeCompletedFrame(GraphicsResourceAllocator allocator, boolean outline,
            CameraRenderState camera, GpuBufferSlice fog, Vector4f fogColor, boolean sky, boolean consistentDepth,
            CallbackInfo callback) {
        BackpackClientGameTests.observeNativeFrameCompletion(levelRenderState);
    }
}
