package com.kadamitas.fabricatedbackpacks.client.render;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.world.entity.EquipmentSlot;
import java.util.Map;

/** Uses the vanilla animated torso pose, including armor, crouching, swimming and riding. */
final class BackpackRenderLayer extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    private final Map<BackpackTier, NativeBackpackModel> models;
    private final BackpackDisplayState display = new BackpackDisplayState();
    BackpackRenderLayer(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent,
                        Map<BackpackTier, NativeBackpackModel> models) {
        super(parent);
        this.models = Map.copyOf(models);
    }
    @Override public void render(PoseStack poses, MultiBufferSource buffers, int light, AbstractClientPlayer player,
                                 float limbSwing, float limbAmount, float partialTick, float age, float headYaw, float headPitch) {
        if (player.isSpectator() || player.isInvisible()) return;
        var stack = BackpackEquipment.visual(player);
        BackpackVisualState backpack = BackpackVisualState.from(stack);
        NativeBackpackModel model = backpack.present() ? models.get(backpack.tier()) : null;
        if (model == null) return;
        poses.pushPose();
        try {
            getParentModel().body.translateAndRotate(poses);
            model.applyWearTransform(poses, !player.getItemBySlot(EquipmentSlot.CHEST).isEmpty());
            int overlay = LivingEntityRenderer.getOverlayCoords(player, 0);
            for (var group : model.groups()) group.model().render(poses, buffers.getBuffer(RenderType.entityCutout(group.texture())),
                    light, overlay, group.color(backpack));
            display.extract(stack, Minecraft.getInstance().getItemRenderer(), player.level(), player, player.getId());
            display.renderWorn(poses, buffers, light, overlay);
        } finally { poses.popPose(); }
    }
}
