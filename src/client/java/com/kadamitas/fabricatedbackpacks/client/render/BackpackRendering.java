package com.kadamitas.fabricatedbackpacks.client.render;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import java.util.Arrays;

/** Native item/block tint providers and the independent player equipment layer. */
public final class BackpackRendering {
    private static boolean initialized;
    private BackpackRendering() {}
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        Block[] blocks = Arrays.stream(BackpackTier.values()).map(BackpackRegistry::block).toArray(Block[]::new);
        ColorProviderRegistry.BLOCK.register((state, view, position, index) -> {
            if (index < 0 || index > 1) return -1;
            ItemStack stack = view != null && position != null && view.getBlockEntity(position) instanceof BackpackBlockEntity backpack
                    ? backpack.stack() : ItemStack.EMPTY;
            return BackpackVisualState.color(stack, index);
        }, blocks);
        ColorProviderRegistry.ITEM.register((stack, index) -> index >= 0 && index <= 1 ? BackpackVisualState.color(stack, index) : -1,
                Arrays.stream(blocks).map(Block::asItem).toArray(net.minecraft.world.item.Item[]::new));
        BlockEntityRendererRegistry.register(BackpackRegistry.BLOCK_ENTITY, BackpackBlockRenderer::new);
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, registration, context) -> {
            if (renderer instanceof PlayerRenderer player) registration.register(new BackpackRenderLayer(player,
                    NativeBackpackModel.load(Minecraft.getInstance().getResourceManager())));
        });
    }
}
