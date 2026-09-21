package com.kadamitas.fabricatedbackpacks.client.render;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.Arrays;
import java.util.List;

/** Registers block dye colors and the native, armor-independent worn layer. */
public final class BackpackRendering {
    static final ContextKey<BackpackVisualState> WORN = new ContextKey<>(BackpackRegistry.id("equipped_visual"));
    static final ContextKey<BackpackDisplayState> DISPLAY = new ContextKey<>(BackpackRegistry.id("equipped_display"));
    private static boolean initialized;

    private BackpackRendering() {}

    public static void initialize(IEventBus modBus) {
        if (initialized) return;
        initialized = true;
        modBus.addListener((RegisterColorHandlersEvent.BlockTintSources event) -> {
            Block[] blocks = Arrays.stream(BackpackTier.values()).map(BackpackRegistry::block).toArray(Block[]::new);
            event.register(List.of(new BackpackTint(0), new BackpackTint(1)), blocks);
        });
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(BackpackRegistry.BLOCK_ENTITY, BackpackBlockRenderer::new));
        modBus.addListener((EntityRenderersEvent.AddLayers event) -> {
            var models = NativeBackpackModel.load(event.getContext().getResourceManager());
            for (var skin : event.getSkins()) {
                var player = event.getPlayerRenderer(skin);
                if (player != null) player.addLayer(new BackpackRenderLayer(player, models));
                var mannequin = event.getMannequinRenderer(skin);
                if (mannequin != null) mannequin.addLayer(new BackpackRenderLayer(mannequin, models));
            }
        });
        modBus.addListener((RegisterRenderStateModifiersEvent event) ->
                event.registerAvatarEntityModifier(new AvatarRenderStateModifier() {
                    @Override public <T extends Avatar & ClientAvatarEntity> void accept(T avatar, AvatarRenderState state) {
                        capture(avatar, state);
                    }
                }));
    }

    public static void capture(Avatar avatar, AvatarRenderState state) {
        ItemStack backpack = avatar instanceof Player player ? BackpackEquipment.visual(player) : ItemStack.EMPTY;
        BackpackVisualState visual = BackpackVisualState.from(backpack);
        state.setRenderData(WORN, visual);
        // Vanilla refreshes showCape on each extraction, so it returns as soon
        // as the independent backpack slot is empty. Other visibility rules stay intact.
        if (visual.present()) state.showCape = false;
        BackpackDisplayState display = new BackpackDisplayState();
        if (avatar instanceof Player player) display.extract(backpack, Minecraft.getInstance().getItemModelResolver(),
                player.level(), player, player.getId());
        state.setRenderData(DISPLAY, display);
    }

    private record BackpackTint(int layer) implements BlockTintSource {
        @Override public int color(BlockState state) { return BackpackVisualState.color(ItemStack.EMPTY, layer); }
        @Override public int colorInWorld(BlockState state, BlockAndTintGetter view, BlockPos position) {
            var colors = view.getModelData(position).get(BackpackBlockEntity.COLOR_MODEL);
            return colors == null ? color(state) : net.minecraft.util.ARGB.opaque(colors.get(layer));
        }
    }
}
