package com.kadamitas.fabricatedbackpacks.platform.transfer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluids;

public record FluidVariant(Fluid getFluid, DataComponentPatch getComponentsPatch) implements TransferVariant<Fluid> {
    // Keep the existing saved component shape (fluid + component patch), including
    // empty filter slots. Native FluidStack's id/amount codec is not save-compatible.
    public static final Codec<FluidVariant> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BuiltInRegistries.FLUID.byNameCodec().fieldOf("fluid").forGetter(FluidVariant::getFluid),
            DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY).forGetter(FluidVariant::getComponentsPatch)
    ).apply(instance, FluidVariant::of));
    public static final StreamCodec<RegistryFriendlyByteBuf, FluidVariant> PACKET_CODEC = ByteBufCodecs.fromCodecWithRegistries(CODEC);
    public static FluidVariant blank() { return new FluidVariant(Fluids.EMPTY, DataComponentPatch.EMPTY); }
    public static FluidVariant of(Fluid fluid) { return of(fluid, DataComponentPatch.EMPTY); }
    public static FluidVariant of(Fluid fluid, DataComponentPatch patch) {
        return new FluidVariant(fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid, patch);
    }
    @Override public Fluid getObject() { return getFluid(); }
    @Override public DataComponentMap getComponents() { return net.minecraft.core.component.PatchedDataComponentMap.fromPatch(DataComponentMap.EMPTY, getComponentsPatch); }
    @Override public boolean isBlank() { return getFluid() == Fluids.EMPTY; }
}
