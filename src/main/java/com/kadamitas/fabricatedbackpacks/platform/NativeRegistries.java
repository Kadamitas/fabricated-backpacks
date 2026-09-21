package com.kadamitas.fabricatedbackpacks.platform;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraftforge.registries.RegisterEvent;

/** Preserve shared bootstrap dependency order while honoring each Forge RegisterEvent. */
public final class NativeRegistries {
    private record Entry<T>(ResourceKey<? extends Registry<T>> registry, Identifier id, T value) {
        void register(RegisterEvent event) { event.register(registry, id, () -> value); }
    }
    private static final List<Entry<?>> PENDING = new ArrayList<>();
    private NativeRegistries() {}
    public static <T> T register(Registry<? super T> registry, Identifier id, T value) {
        @SuppressWarnings({"rawtypes", "unchecked"}) Entry<?> entry = new Entry((ResourceKey)registry.key(), id, value);
        PENDING.add(entry); return value;
    }
    public static <T> T register(Registry<? super T> registry, String id, T value) { return register(registry, Identifier.parse(id), value); }
    public static <T> T register(Registry<? super T> registry, ResourceKey<T> key, T value) { return register(registry, key.identifier(), value); }
    public static void flush(RegisterEvent event) {
        var iterator = PENDING.iterator();
        while (iterator.hasNext()) { var entry = iterator.next(); if (entry.registry().equals(event.getRegistryKey())) { entry.register(event); iterator.remove(); } }
    }
    public static void verifyComplete() { if (!PENDING.isEmpty()) throw new IllegalStateException("Unregistered mod entries: " + PENDING.stream().map(Entry::id).toList()); }
}
