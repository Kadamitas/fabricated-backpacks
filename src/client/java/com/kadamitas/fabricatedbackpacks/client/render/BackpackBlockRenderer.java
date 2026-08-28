package com.kadamitas.fabricatedbackpacks.client.render;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlock;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Unit;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/** The shell remains chunk geometry; only the original hinged flap and selected item are dynamic. */
final class BackpackBlockRenderer implements BlockEntityRenderer<BackpackBlockEntity, BackpackBlockRenderer.State> {
    private static final int MAX_VIEW_DISTANCE = 128;
    // Includes the entire hinge sweep, all four facings and the exterior icon.
    // This small overestimate prevents edge clipping without making every bag global geometry.
    private static final AABB LOCAL_BOUNDS = new AABB(-.25, -.0625, -.25, 1.25, 1.375, 1.25);
    private final ItemModelResolver resolver;
    private final Map<BackpackTier, NativeBackpackModel> flaps;

    BackpackBlockRenderer(BlockEntityRendererProvider.Context context) {
        resolver = context.itemModelResolver();
        flaps = NativeBackpackModel.loadFlaps(Minecraft.getInstance().getResourceManager());
    }

    @Override public State createRenderState() { return new State(); }
    @Override public int getViewDistance() { return MAX_VIEW_DISTANCE; }
    @Override public boolean shouldRenderOffScreen() { return false; }

    @Override public boolean shouldRender(BackpackBlockEntity entity, Vec3 camera) {
        Minecraft client = Minecraft.getInstance();
        int distance = Math.min(MAX_VIEW_DISTANCE, (client.options.getEffectiveRenderDistance() + 1) * 16);
        if (entity.isRemoved() || !Vec3.atCenterOf(entity.getBlockPos()).closerThan(camera, distance)) return false;
        var frustum = client.gameRenderer.mainCamera().getCullFrustum();
        return frustum == null || frustum.isVisible(LOCAL_BOUNDS.move(entity.getBlockPos()));
    }

    @Override public void extractRenderState(BackpackBlockEntity entity, State state, float partialTick, Vec3 camera,
                                           ModelFeatureRenderer.CrumblingOverlay breaking) {
        BlockEntityRenderer.super.extractRenderState(entity, state, partialTick, camera, breaking);
        state.rotation = 180F - entity.getBlockState().getValue(BackpackBlock.FACING).toYRot();
        state.visual = BackpackVisualState.from(entity.stack());
        state.flap = state.visual.present() ? flaps.get(state.visual.tier()) : null;
        state.openness = entity.lidOpenness(partialTick);
        state.display.extract(entity.stack(), resolver, entity.getLevel(), null, entity.getBlockPos().hashCode());
    }

    @Override public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.flap == null && state.display.item.isEmpty()) return;
        poses.pushPose();
        try {
            poses.translate(.5F, 0F, .5F);
            poses.mulPose(Axis.YP.rotationDegrees(state.rotation));
            poses.translate(-.5F, 0F, -.5F);
            if (state.flap != null) {
                poses.pushPose();
                try {
                    state.flap.applyFlapTransform(poses, state.openness);
                    for (NativeBackpackModel.MaterialGroup group : state.flap.groups())
                        collector.submitModel(group.model(), Unit.INSTANCE, poses, RenderTypes.entityCutout(group.texture()),
                                state.lightCoords, OverlayTexture.NO_OVERLAY, group.color(state.visual), null, 0, state.breakProgress);
                } finally { poses.popPose(); }
            }
            state.display.submitPlaced(poses, collector, state.lightCoords, OverlayTexture.NO_OVERLAY);
        } finally { poses.popPose(); }
    }

    static final class State extends BlockEntityRenderState {
        final BackpackDisplayState display = new BackpackDisplayState();
        BackpackVisualState visual = BackpackVisualState.EMPTY;
        NativeBackpackModel flap;
        float openness;
        float rotation;
    }
}
