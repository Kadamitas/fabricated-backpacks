package com.kadamitas.fabricatedbackpacks.client.browser;

import net.fabricmc.fabric.api.transfer.v1.client.fluid.FluidVariantRendering;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Registry fluids use their own names, tint and native still sprite, even when they have no bucket item. */
public final class FluidPresentation {
    private FluidPresentation() {}

    public static Optional<Identifier> canonical(Identifier id) {
        return BuiltInRegistries.FLUID.getOptional(id).filter(fluid -> !fluid.defaultFluidState().isEmpty()).flatMap(fluid -> {
            try { return Optional.ofNullable(BuiltInRegistries.FLUID.getKey(FluidVariant.of(fluid).getFluid())); }
            catch (IllegalArgumentException invalid) { return Optional.empty(); }
        });
    }

    public static Component name(Identifier id) {
        return variant(id).map(FluidVariantAttributes::getName).orElseGet(() -> Component.literal(id.toString()));
    }

    public static List<Component> tooltip(Identifier id) {
        List<Component> lines = new ArrayList<>(variant(id).map(FluidVariantRendering::getTooltip)
                .orElseGet(() -> List.of(Component.literal(id.toString()))));
        if (lines.stream().noneMatch(line -> line.getString().equals(id.toString())))
            lines.add(Component.literal(id.toString()).withStyle(ChatFormatting.DARK_GRAY));
        return List.copyOf(lines);
    }

    public static void draw(GuiGraphicsExtractor graphics, Identifier id, int x, int y) {
        var variant = variant(id).orElse(null);
        if (variant == null) return;
        var sprite = Minecraft.getInstance().getModelManager().getFluidStateModelSet()
                .get(variant.getFluid().defaultFluidState()).stillMaterial().sprite();
        int color = FluidVariantRendering.getColor(variant) | 0xFF000000;
        if (sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
            // Some registry fluids have no world model. A tinted droplet is an explicit fluid icon,
            // not a fabricated bucket item or Minecraft's missing-texture checkerboard.
            graphics.fill(x + 7, y + 1, x + 9, y + 4, color);
            graphics.fill(x + 5, y + 4, x + 11, y + 7, color);
            graphics.fill(x + 3, y + 7, x + 13, y + 12, color);
            graphics.fill(x + 5, y + 12, x + 11, y + 15, color);
            graphics.fill(x + 5, y + 8, x + 6, y + 11, 0xFFECF1E7);
        } else graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, 16, 16, color);
    }

    private static Optional<FluidVariant> variant(Identifier id) {
        return canonical(id).flatMap(BuiltInRegistries.FLUID::getOptional).map(FluidVariant::of);
    }
}
