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
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;

public final class FluidStorage {
    public static final BlockLookup<Storage<FluidVariant>, IFluidHandler> SIDED = new BlockLookup<>(ForgeCapabilities.FLUID_HANDLER,
            handler -> new NativeStorage<>(ForgeResourceAdapters.fluids(handler), value -> value, value -> value, 81),
            storage -> ForgeResourceAdapters.fluids(NativeStorage.export(storage, value -> value, value -> value, 81)));
    public static final ItemLookup<Storage<FluidVariant>, IFluidHandlerItem> ITEM = new ItemLookup<>(ForgeCapabilities.FLUID_HANDLER_ITEM,
            handler -> new NativeStorage<>(ForgeResourceAdapters.fluids(handler), value -> value, value -> value, 81),
            (storage, context) -> ForgeResourceAdapters.fluidItem(NativeStorage.export(storage, value -> value, value -> value, 81), context));
    private static final Map<Item, List<Function<ContainerItemContext, Storage<FluidVariant>>>> COMBINED = new LinkedHashMap<>();
    static {
        ITEM.setFallback((stack, context) -> ForgeItemFluidStorage.create(context));
        ITEM.registerForItems((stack, context) -> {
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            return contents != null && contents.is(Potions.WATER)
                    ? new FullItemFluidStorage(context, Items.GLASS_BOTTLE, FluidVariant.of(Fluids.WATER), FluidConstants.BOTTLE) : null;
        }, Items.POTION);
        combinedItemApiProvider(Items.GLASS_BOTTLE).register(context -> new EmptyItemFluidStorage(context,
                empty -> ItemVariant.of(PotionContents.createItemStack(Items.POTION, Potions.WATER)), Fluids.WATER, FluidConstants.BOTTLE));
    }
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
