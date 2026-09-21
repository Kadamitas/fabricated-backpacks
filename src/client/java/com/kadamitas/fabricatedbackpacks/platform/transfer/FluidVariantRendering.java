package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

/** Forge fluid extensions receive the full native stack, including its custom tag. */
public final class FluidVariantRendering {
    private FluidVariantRendering() {}

    public static int getColor(FluidVariant variant) {
        return IClientFluidTypeExtensions.of(variant.getFluid()).getTintColor(ForgeResourceAdapters.fluid(variant, 1000));
    }

    public static TextureAtlasSprite getSprite(FluidVariant variant) {
        Minecraft client = Minecraft.getInstance();
        var texture = IClientFluidTypeExtensions.of(variant.getFluid()).getStillTexture(ForgeResourceAdapters.fluid(variant, 1000));
        return texture == null ? client.getModelManager().getFluidStateModelSet()
                .get(variant.getFluid().defaultFluidState()).stillMaterial().sprite()
                : client.getAtlasManager().get(new SpriteId(TextureAtlas.LOCATION_BLOCKS, texture));
    }

    public static List<Component> getTooltip(FluidVariant variant) {
        return List.of(ForgeResourceAdapters.fluid(variant, 1000).getDisplayName());
    }
}
