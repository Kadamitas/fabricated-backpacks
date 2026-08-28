package com.kadamitas.fabricatedbackpacks.client.render;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlock;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.Vec3;
import java.util.Map;

/** The shell remains chunk geometry; only the original hinged flap and selected icon are dynamic. */
final class BackpackBlockRenderer implements BlockEntityRenderer<BackpackBlockEntity> {
    private static final int MAX_VIEW_DISTANCE = 128;
    private final Map<BackpackTier, NativeBackpackModel> flaps;
    private final BackpackDisplayState display = new BackpackDisplayState();
    BackpackBlockRenderer(BlockEntityRendererProvider.Context context) {
        flaps = NativeBackpackModel.loadFlaps(Minecraft.getInstance().getResourceManager());
    }
    @Override public int getViewDistance() { return MAX_VIEW_DISTANCE; }
    @Override public boolean shouldRenderOffScreen(BackpackBlockEntity entity) { return false; }
    @Override public boolean shouldRender(BackpackBlockEntity entity, Vec3 camera) {
        int distance = Math.min(MAX_VIEW_DISTANCE, (Minecraft.getInstance().options.getEffectiveRenderDistance() + 1) * 16);
        return !entity.isRemoved() && Vec3.atCenterOf(entity.getBlockPos()).closerThan(camera, distance);
    }
    @Override public void render(BackpackBlockEntity entity, float partialTick, PoseStack poses,
                                 MultiBufferSource buffers, int light, int overlay) {
        BackpackVisualState visual = BackpackVisualState.from(entity.stack());
        NativeBackpackModel flap = visual.present() ? flaps.get(visual.tier()) : null;
        display.extract(entity.stack(), Minecraft.getInstance().getItemRenderer(), entity.getLevel(), null, entity.getBlockPos().hashCode());
        if (flap == null && display.isEmpty()) return;
        poses.pushPose();
        try {
            poses.translate(.5F, 0F, .5F);
            poses.mulPose(Axis.YP.rotationDegrees(180F - entity.getBlockState().getValue(BackpackBlock.FACING).toYRot()));
            poses.translate(-.5F, 0F, -.5F);
            if (flap != null) {
                poses.pushPose();
                try {
                    flap.applyFlapTransform(poses, entity.lidOpenness(partialTick));
                    for (var group : flap.groups()) group.model().render(poses,
                            buffers.getBuffer(RenderType.entityCutout(group.texture())), light, overlay, group.color(visual));
                } finally { poses.popPose(); }
            }
            display.renderPlaced(poses, buffers, light, overlay);
        } finally { poses.popPose(); }
    }
}
