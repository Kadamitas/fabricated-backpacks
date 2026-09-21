package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;

public final class NativeCapabilities {
    static final List<BlockLookup<?, ?>> BLOCKS = new ArrayList<>();
    static final List<ItemLookup<?, ?>> ITEMS = new ArrayList<>();
    /** Handlers handed to other mods, invalidated when a block's exposed handlers change or it is removed. */
    private static final Map<BlockEntity, List<LazyOptional<?>>> ISSUED = Collections.synchronizedMap(new WeakHashMap<>());
    private NativeCapabilities() {}
    /** The Forge counterpart of a capability invalidation: consumers re-query through their invalidation listeners. */
    public static void invalidate(BlockEntity entity) {
        List<LazyOptional<?>> issued = ISSUED.remove(entity);
        if (issued != null) for (LazyOptional<?> optional : issued) optional.invalidate();
    }
    /** Also used by owned containers whose vanilla superclass masks attached item capabilities. */
    public static <T> LazyOptional<T> getBlockCapability(BlockEntity entity, Capability<T> capability, Direction side) {
        if (entity.isRemoved()) return LazyOptional.empty();
        for (var lookup : BLOCKS) if (lookup.capability == capability) {
            Object value = lookup.exported(entity, side);
            if (value == null) continue;
            LazyOptional<T> optional = LazyOptional.of(() -> value).cast();
            ISSUED.computeIfAbsent(entity, ignored -> Collections.synchronizedList(new ArrayList<>())).add(optional);
            return optional;
        }
        return LazyOptional.empty();
    }
    public static void initialize(Object ignoredModBus) {
        AttachCapabilitiesEvent.BlockEntities.BUS.addListener(event -> {
            BlockEntity entity = event.getObject();
            event.addListener(() -> invalidate(entity));
            event.addCapability(Identifier.fromNamespaceAndPath("fabricated_backpacks", "resources"), new ICapabilityProvider() {
                @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                    return getBlockCapability(entity, capability, side);
                }
            });
        });
        AttachCapabilitiesEvent.ItemStacks.BUS.addListener(event -> event.addCapability(Identifier.fromNamespaceAndPath("fabricated_backpacks", "resources"), new ICapabilityProvider() {
            @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
                for (var lookup : ITEMS) if (lookup.capability == capability) {
                    Object value = lookup.exported(event.getObject(), ContainerItemContext.ofStack(event.getObject()));
                    if (value != null) return LazyOptional.of(() -> value).cast();
                }
                return LazyOptional.empty();
            }
        }));
    }
}
