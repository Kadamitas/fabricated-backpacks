package com.kadamitas.fabricatedbackpacks.client.render;

import com.kadamitas.fabricatedbackpacks.item.BackpackDisplay;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/** A resolved item model snapshot shared by placed and worn displays. No live inventory is retained. */
final class BackpackDisplayState {
    private static final float POCKET_Y = 4.05F / 16F;
    private static final float POCKET_FRONT = 1.45F / 16F;
    private static final float DISPLAY_SIZE = 3.25F / 16F;
    private ItemStack item = ItemStack.EMPTY;
    private BakedModel model;
    private ItemRenderer renderer;
    boolean isEmpty() { return item.isEmpty(); }
    private float rotation;
    private float scale;
    private float centerX;
    private float centerY;
    private float depth;

    void extract(ItemStack backpack, ItemRenderer renderer, Level level, LivingEntity wearer, int seed) {
        item = ItemStack.EMPTY;
        var selectedDisplay = BackpackDisplay.from(backpack);
        if (selectedDisplay.isEmpty()) return;
        BackpackDisplay display = selectedDisplay.orElseThrow();
        ItemStack selected = display.icon();
        item = selected.copyWithCount(1);
        if (item.isEmpty()) return;
        this.renderer = renderer;
        model = renderer.getModel(item, level, wearer, seed);
        rotation = display.rotation();
        AABB bounds = DisplayModelBounds.of(model);
        double width = bounds.maxX - bounds.minX;
        double height = bounds.maxY - bounds.minY;
        double angle = Math.toRadians(rotation);
        double rotatedWidth = Math.abs(Math.cos(angle)) * width + Math.abs(Math.sin(angle)) * height;
        double rotatedHeight = Math.abs(Math.sin(angle)) * width + Math.abs(Math.cos(angle)) * height;
        double extent = Math.max(rotatedWidth, rotatedHeight);
        if (!Double.isFinite(extent) || !Double.isFinite(bounds.maxZ)) { item = ItemStack.EMPTY; return; }
        scale = (float) Math.clamp(DISPLAY_SIZE / Math.max(extent, .001), .01, 1);
        centerX = (float) ((bounds.minX + bounds.maxX) / 2);
        centerY = (float) ((bounds.minY + bounds.maxY) / 2);
        // One depth step is 1/16 of a texture pixel. Fit the model's back face
        // against the leather first, so block items do not sink into the shell.
        depth = (float) bounds.maxZ * scale + display.depth() / 256F;
    }

    void renderPlaced(PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        if (item.isEmpty()) return;
        poses.pushPose();
        try {
            poses.translate(.5F, POCKET_Y, POCKET_FRONT - depth);
            renderItem(poses, buffers, light, overlay);
        } finally { poses.popPose(); }
    }

    void renderWorn(PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        if (item.isEmpty()) return;
        poses.pushPose();
        try {
            // Original cuboids map to torso coordinates by offset minus source.
            // Flip item Y/Z with a proper rotation, preserving readable faces.
            poses.translate(0F, 13.75F / 16F - POCKET_Y, 15.375F / 16F - POCKET_FRONT + depth);
            poses.mulPose(Axis.XP.rotationDegrees(180F));
            renderItem(poses, buffers, light, overlay);
        } finally { poses.popPose(); }
    }

    private void renderItem(PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        poses.mulPose(Axis.ZP.rotationDegrees(rotation));
        poses.scale(scale, scale, scale);
        poses.translate(-centerX, -centerY, 0F);
        renderer.render(item, ItemDisplayContext.FIXED, false, poses, buffers, light, overlay, model);
    }
}
