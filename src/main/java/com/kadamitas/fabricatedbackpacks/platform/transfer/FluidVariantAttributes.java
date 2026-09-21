package com.kadamitas.fabricatedbackpacks.platform.transfer;

import net.minecraft.network.chat.Component;

public final class FluidVariantAttributes {
    public static Component getName(FluidVariant fluid) { return ForgeResourceAdapters.fluid(fluid, 1000).getDisplayName(); }
    private FluidVariantAttributes() {}
}
