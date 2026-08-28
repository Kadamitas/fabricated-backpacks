package com.kadamitas.fabricatedbackpacks.world;

import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;

public final class WorldComponents {
    public record DeferredLoot(Identifier table, long seed, int rolls, float luck) {
        public static final Codec<DeferredLoot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Identifier.CODEC.fieldOf("table").forGetter(DeferredLoot::table), Codec.LONG.fieldOf("seed").forGetter(DeferredLoot::seed),
                Codec.intRange(1, 6).fieldOf("rolls").forGetter(DeferredLoot::rolls),
                Codec.floatRange(0, 100).fieldOf("luck").forGetter(DeferredLoot::luck)).apply(instance, DeferredLoot::new));
        public DeferredLoot {
            if (table == null || rolls < 1 || rolls > 6 || !Float.isFinite(luck) || luck < 0 || luck > 100)
                throw new IllegalArgumentException("Invalid deferred loot plan");
        }
    }
    public static final DataComponentType<DeferredLoot> DEFERRED_LOOT = component("deferred_loot", DeferredLoot.CODEC);
    public static final DataComponentType<InventorySnapshot> EXTRA_ITEMS = component("extra_items", InventorySnapshot.CODEC);
    public static final AttachmentType<Boolean> SPAWN_CHECKED = AttachmentRegistry.create(id("spawn_checked"),
            builder -> builder.persistent(Codec.BOOL));
    public static final AttachmentType<Float> PENDING_DIFFICULTY = AttachmentRegistry.create(id("pending_spawn_difficulty"),
            builder -> builder.persistent(Codec.floatRange(0, 100)));
    private WorldComponents() { }
    public static void initialize() { }
    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath("fabricated_backpacks", path); }
    private static <T> DataComponentType<T> component(String id, Codec<T> codec) {
        return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, id(id), DataComponentType.<T>builder()
                .persistent(codec).networkSynchronized(ByteBufCodecs.fromCodecWithRegistries(codec)).cacheEncoding().build());
    }
}
