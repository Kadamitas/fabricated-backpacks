package com.kadamitas.fabricatedbackpacks.client.automation;

import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineBlock;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import java.util.List;

/** Only the wheel, crank, constant-length rod and piston move; the rest is chunk geometry. */
final class SteamEngineRenderer implements BlockEntityRenderer<SteamEngineBlockEntity> {
    private final NativeSteamEngineModel model;
    SteamEngineRenderer(BlockEntityRendererProvider.Context context) {
        model = NativeSteamEngineModel.load(Minecraft.getInstance().getResourceManager());
    }
    @Override public int getViewDistance() { return 96; }
    @Override public boolean shouldRenderOffScreen(SteamEngineBlockEntity entity) { return false; }
    @Override public void render(SteamEngineBlockEntity entity, float partialTick, PoseStack poses,
                                 MultiBufferSource buffers, int light, int overlay) {
        float phase = entity.crankAngle(partialTick);
        double pinX = model.wheelX + model.radius * Math.cos(phase);
        double deltaY = model.radius * Math.sin(phase);
        float sliderX = (float) (pinX - Math.sqrt(model.rodLength * model.rodLength - deltaY * deltaY));
        float rodAngle = (float) Math.atan2(deltaY, pinX - sliderX);
        poses.pushPose();
        try {
            poses.translate(.5F, 0F, .5F);
            poses.mulPose(Axis.YP.rotationDegrees(180F - entity.getBlockState().getValue(SteamEngineBlock.FACING).toYRot()));
            poses.translate(-.5F, 0F, -.5F);
            group(model.wheel, model.wheelX, model.wheelY, model.wheelZ, phase, poses, buffers, light, overlay);
            group(model.rod, sliderX, model.wheelY, model.rodZ, rodAngle, poses, buffers, light, overlay);
            group(model.piston, sliderX, model.wheelY, model.rodZ, 0, poses, buffers, light, overlay);
        } finally { poses.popPose(); }
    }
    private static void group(List<NativeSteamEngineModel.MaterialGroup> groups, float x, float y, float z, float angle,
                              PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        poses.pushPose();
        try {
            poses.translate(x / 16F, y / 16F, z / 16F);
            poses.mulPose(Axis.ZP.rotation(angle));
            for (var group : groups) group.model().render(poses, buffers.getBuffer(RenderType.entityCutout(group.texture())), light, overlay, -1);
        } finally { poses.popPose(); }
    }
}
