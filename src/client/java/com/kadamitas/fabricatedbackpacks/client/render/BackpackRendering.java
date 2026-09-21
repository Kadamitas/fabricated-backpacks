package com.kadamitas.fabricatedbackpacks.client.render;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.entity.ClientMannequin;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
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
    private static boolean initialized;

    private BackpackRendering() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        RegisterColorHandlersEvent.Block.BUS.addListener((RegisterColorHandlersEvent.Block event) -> {
            Block[] blocks = Arrays.stream(BackpackTier.values()).map(BackpackRegistry::block).toArray(Block[]::new);
            event.register(List.of(new BackpackTint(0), new BackpackTint(1)), blocks);
        });
        EntityRenderersEvent.RegisterRenderers.BUS.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(BackpackRegistry.BLOCK_ENTITY, BackpackBlockRenderer::new));
        EntityRenderersEvent.AddLayers.BUS.addListener((EntityRenderersEvent.AddLayers event) -> {
            var models = NativeBackpackModel.load(event.getContext().getResourceManager());
            for (var skin : event.getModelTypes()) {
                AvatarRenderer<AbstractClientPlayer> player = event.getPlayerRenderer(skin);
                if (player != null) player.addLayer(new BackpackRenderLayer(player, models));
                AvatarRenderer<ClientMannequin> mannequin = event.getMannequinRenderer(skin);
                if (mannequin != null) mannequin.addLayer(new BackpackRenderLayer(mannequin, models));
            }
        });
    }

    public static void capture(Avatar avatar, AvatarRenderState state) {
        ItemStack backpack = avatar instanceof Player player ? BackpackEquipment.visual(player) : ItemStack.EMPTY;
        BackpackVisualState visual = BackpackVisualState.from(backpack);
        BackpackAvatarState captured = (BackpackAvatarState) state;
        captured.fabricatedBackpacks$visual(visual);
        // Vanilla refreshes showCape on each extraction, so it returns as soon
        // as the independent backpack slot is empty. Other visibility rules stay intact.
        if (visual.present()) state.showCape = false;
        BackpackDisplayState display = new BackpackDisplayState();
        if (avatar instanceof Player player) display.extract(backpack, Minecraft.getInstance().getItemModelResolver(),
                player.level(), player, player.getId());
        captured.fabricatedBackpacks$display(display);
    }

    private record BackpackTint(int layer) implements BlockTintSource {
        @Override public int color(BlockState state) { return BackpackVisualState.color(ItemStack.EMPTY, layer); }
        @Override public int colorInWorld(BlockState state, BlockAndTintGetter view, BlockPos position) {
            var manager = view.getModelDataManager();
            var colors = manager == null ? null : manager.getAtOrEmpty(position).get(BackpackBlockEntity.COLOR_MODEL);
            return colors == null ? color(state) : net.minecraft.util.ARGB.opaque(colors.get(layer));
        }
    }
}
