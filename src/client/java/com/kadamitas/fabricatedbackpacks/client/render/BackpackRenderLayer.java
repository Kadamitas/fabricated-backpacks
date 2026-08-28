package com.kadamitas.fabricatedbackpacks.client.render;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Unit;

import java.util.Map;

/** Independent equipment layer; uses the already-animated torso and keeps armor. */
final class BackpackRenderLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private final Map<BackpackTier, NativeBackpackModel> models;

    BackpackRenderLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent,
                        Map<BackpackTier, NativeBackpackModel> models) {
        super(parent);
        this.models = Map.copyOf(models);
    }

    @Override
    public void submit(PoseStack poses, SubmitNodeCollector collector, int light, AvatarRenderState state,
                       float headYaw, float headPitch) {
        BackpackVisualState backpack = ((FabricRenderState) state).getDataOrDefault(BackpackRendering.WORN, BackpackVisualState.EMPTY);
        if (!backpack.present() || state.isSpectator || state.isInvisible) return;
        NativeBackpackModel model = models.get(backpack.tier());
        if (model == null) return;
        poses.pushPose();
        try {
            // The vanilla renderer applies entity/body scales before invoking
            // layers. Its model already contains crouch, swim and riding pose.
            getParentModel().body.translateAndRotate(poses);
            // Fit within the torso instead of extending the original block-depth
            // mesh toward the knees in a rear third-person view.
            model.applyWearTransform(poses, !state.chestEquipment.isEmpty());
            int overlay = LivingEntityRenderer.getOverlayCoords(state, 0);
            for (NativeBackpackModel.MaterialGroup group : model.groups()) {
                collector.submitModel(group.model(), Unit.INSTANCE, poses, RenderTypes.entityCutout(group.texture()),
                        light, overlay, group.color(backpack), null, state.outlineColor, null);
            }
            BackpackDisplayState display = ((FabricRenderState) state).getData(BackpackRendering.DISPLAY);
            if (display != null) display.submitWorn(poses, collector, light, overlay, state.outlineColor);
        } finally {
            poses.popPose();
        }
    }
}
