package com.kadamitas.fabricatedbackpacks.client.mixin;

import com.kadamitas.fabricatedbackpacks.client.render.BackpackAvatarState;
import com.kadamitas.fabricatedbackpacks.client.render.BackpackDisplayState;
import com.kadamitas.fabricatedbackpacks.client.render.BackpackVisualState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Forge has no generic render-state attachment API, so own these two fields. */
@Mixin(value = AvatarRenderState.class, remap = false)
abstract class AvatarRenderStateMixin implements BackpackAvatarState {
    @Unique private BackpackVisualState fabricatedBackpacks$visual = BackpackVisualState.EMPTY;
    @Unique private BackpackDisplayState fabricatedBackpacks$display;

    @Override public BackpackVisualState fabricatedBackpacks$visual() { return fabricatedBackpacks$visual; }
    @Override public void fabricatedBackpacks$visual(BackpackVisualState visual) { fabricatedBackpacks$visual = visual; }
    @Override public BackpackDisplayState fabricatedBackpacks$display() { return fabricatedBackpacks$display; }
    @Override public void fabricatedBackpacks$display(BackpackDisplayState display) { fabricatedBackpacks$display = display; }
}
