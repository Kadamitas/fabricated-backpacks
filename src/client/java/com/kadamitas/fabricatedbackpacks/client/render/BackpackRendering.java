package com.kadamitas.fabricatedbackpacks.client.render;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BlockTintsFactory;
import net.fabricmc.fabric.api.client.rendering.v1.FabricRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.Arrays;

/** Registers block dye colors and the native, armor-independent worn layer. */
public final class BackpackRendering {
    static final RenderStateDataKey<BackpackVisualState> WORN = RenderStateDataKey.create(() -> "fabricated_backpacks:equipped_visual");
    static final RenderStateDataKey<BackpackDisplayState> DISPLAY = RenderStateDataKey.create(() -> "fabricated_backpacks:equipped_display");
    private static boolean initialized;

    private BackpackRendering() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        Block[] blocks = Arrays.stream(BackpackTier.values()).map(BackpackRegistry::block).toArray(Block[]::new);
        BlockColorRegistry.register((BlockTintsFactory) (state, view, position, colors) -> {
            ItemStack stack = view != null && position != null && view.getBlockEntity(position) instanceof BackpackBlockEntity backpack
                    ? backpack.stack() : ItemStack.EMPTY;
            colors.add(BackpackVisualState.color(stack, 0));
            colors.add(BackpackVisualState.color(stack, 1));
        }, blocks);
        BlockEntityRendererRegistry.register(BackpackRegistry.BLOCK_ENTITY, BackpackBlockRenderer::new);
        LivingEntityRenderLayerRegistrationCallback.EVENT.register((type, renderer, registration, context) -> {
            if (renderer instanceof AvatarRenderer<?> avatar) {
                registration.register(new BackpackRenderLayer(avatar, NativeBackpackModel.load(context.getResourceManager())));
            }
        });
        // A cape intersects the pack's straps and rear shell; show it again as
        // soon as the independent backpack slot is empty.
        LivingEntityFeatureRenderEvents.ALLOW_CAPE_RENDER.register(state ->
                !((FabricRenderState) state).getDataOrDefault(WORN, BackpackVisualState.EMPTY).present());
    }

    public static void capture(Avatar avatar, AvatarRenderState state) {
        ItemStack backpack = avatar instanceof Player player ? BackpackEquipment.visual(player) : ItemStack.EMPTY;
        ((FabricRenderState) state).setData(WORN, BackpackVisualState.from(backpack));
        BackpackDisplayState display = new BackpackDisplayState();
        if (avatar instanceof Player player) display.extract(backpack, Minecraft.getInstance().getItemModelResolver(),
                player.level(), player, player.getId());
        ((FabricRenderState) state).setData(DISPLAY, display);
    }
}
