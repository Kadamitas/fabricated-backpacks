package com.kadamitas.fabricatedbackpacks.client.browser;

import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Registry fluids use their own names, tint and native still sprite, even when they have no bucket item. */
public final class FluidPresentation {
    private FluidPresentation() {}

    public static Optional<ResourceLocation> canonical(ResourceLocation id) {
        return BuiltInRegistries.FLUID.getOptional(id).filter(fluid -> !fluid.defaultFluidState().isEmpty()).flatMap(fluid -> {
            try { return Optional.ofNullable(BuiltInRegistries.FLUID.getKey(FluidVariant.of(fluid).getFluid())); }
            catch (IllegalArgumentException invalid) { return Optional.empty(); }
        });
    }

    public static Component name(ResourceLocation id) {
        return variant(id).map(FluidVariantAttributes::getName).orElseGet(() -> Component.literal(id.toString()));
    }

    public static List<Component> tooltip(ResourceLocation id) {
        List<Component> lines = new ArrayList<>(variant(id).map(FluidVariantRendering::getTooltip)
                .orElseGet(() -> List.of(Component.literal(id.toString()))));
        if (lines.stream().noneMatch(line -> line.getString().equals(id.toString())))
            lines.add(Component.literal(id.toString()).withStyle(ChatFormatting.DARK_GRAY));
        return List.copyOf(lines);
    }

    public static void draw(GuiGraphics graphics, ResourceLocation id, int x, int y) {
        var variant = variant(id).orElse(null);
        if (variant == null) return;
        var sprite = FluidVariantRendering.getSprite(variant);
        int color = FluidVariantRendering.getColor(variant) | 0xFF000000;
        if (sprite == null || sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
            // Some registry fluids have no world model. A tinted droplet is an explicit fluid icon,
            // not a fabricated bucket item or Minecraft's missing-texture checkerboard.
            graphics.fill(x + 7, y + 1, x + 9, y + 4, color);
            graphics.fill(x + 5, y + 4, x + 11, y + 7, color);
            graphics.fill(x + 3, y + 7, x + 13, y + 12, color);
            graphics.fill(x + 5, y + 12, x + 11, y + 15, color);
            graphics.fill(x + 5, y + 8, x + 6, y + 11, 0xFFECF1E7);
        } else graphics.blit(x, y, 0, 16, 16, sprite,
                (color >> 16 & 255) / 255F, (color >> 8 & 255) / 255F, (color & 255) / 255F, 1F);
    }

    private static Optional<FluidVariant> variant(ResourceLocation id) {
        return canonical(id).flatMap(BuiltInRegistries.FLUID::getOptional).map(FluidVariant::of);
    }
}
