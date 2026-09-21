package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

public final class FluidStorage {
    public static final BlockLookup<Storage<FluidVariant>, ResourceHandler<FluidResource>> SIDED = new BlockLookup<>(Capabilities.Fluid.BLOCK,
            handler -> NativeStorage.adopt(handler, FluidVariant::new, FluidVariant::nativeResource, 81),
            storage -> NativeStorage.export(storage, FluidVariant::new, FluidVariant::nativeResource, 81));
    public static final ItemLookup<Storage<FluidVariant>, ResourceHandler<FluidResource>> ITEM = new ItemLookup<>(Capabilities.Fluid.ITEM,
            handler -> NativeStorage.adopt(handler, FluidVariant::new, FluidVariant::nativeResource, 81),
            storage -> NativeStorage.export(storage, FluidVariant::new, FluidVariant::nativeResource, 81));
    private static final Map<Item, List<Function<ContainerItemContext, Storage<FluidVariant>>>> COMBINED = new LinkedHashMap<>();

    static {
        // NeoForge provides bucket handlers itself; these vanilla bottle providers match Fabric's.
        ITEM.registerForItems((stack, context) -> {
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            return contents != null && contents.is(Potions.WATER)
                    ? new FullItemFluidStorage(context, Items.GLASS_BOTTLE, FluidVariant.of(Fluids.WATER), FluidConstants.BOTTLE) : null;
        }, Items.POTION);
        combinedItemApiProvider(Items.GLASS_BOTTLE).register(context -> new EmptyItemFluidStorage(context,
                empty -> ItemVariant.of(PotionContents.createItemStack(Items.POTION, Potions.WATER)), Fluids.WATER, FluidConstants.BOTTLE));
    }

    /** Every registered provider for one item contributes to a single combined storage, as with Fabric. */
    public record CombinedItemProvider(Item item) {
        public void register(Function<ContainerItemContext, Storage<FluidVariant>> provider) {
            COMBINED.computeIfAbsent(item, key -> {
                List<Function<ContainerItemContext, Storage<FluidVariant>>> providers = new ArrayList<>();
                ITEM.registerForItems((stack, context) -> combine(providers, context), key);
                return providers;
            }).add(provider);
        }
    }
    private static Storage<FluidVariant> combine(List<Function<ContainerItemContext, Storage<FluidVariant>>> providers, ContainerItemContext context) {
        List<Storage<FluidVariant>> parts = new ArrayList<>();
        for (var provider : providers) {
            Storage<FluidVariant> part = provider.apply(context);
            if (part != null) parts.add(part);
        }
        return parts.isEmpty() ? null : new CombinedStorage<>(parts);
    }
    public static CombinedItemProvider combinedItemApiProvider(Item item) { return new CombinedItemProvider(item); }
    private FluidStorage() {}
}
