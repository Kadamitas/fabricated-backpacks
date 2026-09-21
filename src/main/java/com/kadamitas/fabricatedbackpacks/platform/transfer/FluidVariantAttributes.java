package com.kadamitas.fabricatedbackpacks.platform.transfer;

import net.minecraft.network.chat.Component;

public final class FluidVariantAttributes {
    public static Component getName(FluidVariant fluid) { return fluid.nativeResource().toStack(1000).getHoverName(); }
    private FluidVariantAttributes() {}
}
