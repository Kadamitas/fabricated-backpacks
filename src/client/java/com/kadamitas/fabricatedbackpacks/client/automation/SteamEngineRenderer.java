package com.kadamitas.fabricatedbackpacks.client.automation;

import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineBlock;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Unit;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Only the wheel, crank, constant-length rod and piston move; the rest is chunk geometry. */
final class SteamEngineRenderer implements BlockEntityRenderer<SteamEngineBlockEntity, SteamEngineRenderer.State> {
    private final NativeSteamEngineModel model;

    SteamEngineRenderer(BlockEntityRendererProvider.Context context) {
        model = NativeSteamEngineModel.load(Minecraft.getInstance().getResourceManager());
    }
    @Override public State createRenderState() { return new State(); }
    @Override public int getViewDistance() { return 96; }
    @Override public boolean shouldRenderOffScreen() { return false; }

    @Override public void extractRenderState(SteamEngineBlockEntity entity, State state, float partialTick,
                                              Vec3 camera, ModelFeatureRenderer.CrumblingOverlay breaking) {
        BlockEntityRenderer.super.extractRenderState(entity, state, partialTick, camera, breaking);
        state.rotation = 180F - entity.getBlockState().getValue(SteamEngineBlock.FACING).toYRot();
        state.phase = entity.crankAngle(partialTick);
        double pinX = model.wheelX + model.radius * Math.cos(state.phase);
        double deltaY = model.radius * Math.sin(state.phase);
        state.sliderX = (float) (pinX - Math.sqrt(model.rodLength * model.rodLength - deltaY * deltaY));
        state.rodAngle = (float) Math.atan2(deltaY, pinX - state.sliderX);
    }

    @Override public void submit(State state, PoseStack poses, SubmitNodeCollector collector, CameraRenderState camera) {
        poses.pushPose();
        try {
            poses.translate(.5F, 0F, .5F);
            poses.mulPose(Axis.YP.rotationDegrees(state.rotation));
            poses.translate(-.5F, 0F, -.5F);
            group(model.wheel, model.wheelX, model.wheelY, model.wheelZ, state.phase, state, poses, collector);
            group(model.rod, state.sliderX, model.wheelY, model.rodZ, state.rodAngle, state, poses, collector);
            group(model.piston, state.sliderX, model.wheelY, model.rodZ, 0, state, poses, collector);
        } finally { poses.popPose(); }
    }

    private static void group(List<NativeSteamEngineModel.MaterialGroup> groups, float x, float y, float z, float angle,
                              State state, PoseStack poses, SubmitNodeCollector collector) {
        poses.pushPose();
        try {
            poses.translate(x / 16F, y / 16F, z / 16F);
            poses.mulPose(Axis.ZP.rotation(angle));
            for (var group : groups) collector.submitModel(group.model(), Unit.INSTANCE, poses,
                    RenderTypes.entityCutout(group.texture()), state.lightCoords, OverlayTexture.NO_OVERLAY, -1, null, 0, state.breakProgress);
        } finally { poses.popPose(); }
    }

    static final class State extends BlockEntityRenderState {
        float rotation, phase, sliderX, rodAngle;
    }
}
