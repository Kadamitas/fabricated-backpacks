package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.level.material.EmptyFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import java.util.List;

/** Registry-backed fluid components preserve custom fluid data through normal item codecs. */
public final class ResourceComponents {
    public static final int MAX_FLUID_FILTERS = 64;
    public static final DataComponentType<FluidVariant> TANK_FLUID = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE, BackpackRegistry.id("tank_fluid"),
            DataComponentType.<FluidVariant>builder().persistent(FluidVariant.CODEC)
                    .networkSynchronized(FluidVariant.PACKET_CODEC).cacheEncoding().build());
    public static final DataComponentType<List<FluidVariant>> VOID_FLUID_FILTERS = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE, BackpackRegistry.id("void_fluid_filters"),
            DataComponentType.<List<FluidVariant>>builder().persistent(FluidVariant.CODEC.listOf(0, MAX_FLUID_FILTERS)
                            .xmap(List::copyOf, List::copyOf))
                    .networkSynchronized(FluidVariant.PACKET_CODEC.apply(ByteBufCodecs.<net.minecraft.network.RegistryFriendlyByteBuf, FluidVariant>list(MAX_FLUID_FILTERS))
                            .map(List::copyOf, List::copyOf)).cacheEncoding().build());
    public static final Fluid EXPERIENCE = Registry.register(BuiltInRegistries.FLUID,
            BackpackRegistry.id("experience"), new StoredExperienceFluid());

    private ResourceComponents() {}
    /** Calling this during initialization loads and registers the component and fluid above. */
    public static void register() {}
    public static FluidVariant experience() { return FluidVariant.of(EXPERIENCE); }

    /** Experience is a stored resource, not a placeable or flowing world liquid. */
    private static final class StoredExperienceFluid extends EmptyFluid {
        @Override protected boolean isEmpty() { return false; }
        // Fabric fluid variants require a still source identity, even for storage-only resources.
        @Override public boolean isSource(FluidState state) { return true; }
    }
}
