package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;

/** Native fluid model tints and component-aware NeoForge tooltips. */
public final class FluidVariantRendering {
    private FluidVariantRendering() {}

    public static int getColor(FluidVariant variant) {
        var model = Minecraft.getInstance().getModelManager().getFluidStateModelSet()
                .get(variant.getFluid().defaultFluidState());
        return model.fluidTintSource() == null ? -1
                : model.fluidTintSource().colorAsStack(variant.nativeResource().toStack(1000));
    }

    public static List<Component> getTooltip(FluidVariant variant) {
        Minecraft client = Minecraft.getInstance();
        return variant.nativeResource().toStack(1000).getTooltipLines(
                Item.TooltipContext.of(client.level), client.player,
                client.options.advancedItemTooltips ? TooltipFlag.ADVANCED : TooltipFlag.NORMAL);
    }
}
