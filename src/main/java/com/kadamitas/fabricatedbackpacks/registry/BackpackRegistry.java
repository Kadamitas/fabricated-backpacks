package com.kadamitas.fabricatedbackpacks.registry;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlock;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.item.BackpackItem;
import com.kadamitas.fabricatedbackpacks.item.UpgradeItem;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class BackpackRegistry {
    private static final Map<BackpackTier, BackpackBlock> BLOCKS = new EnumMap<>(BackpackTier.class);
    private static final Map<BackpackTier, Item> BACKPACKS = new EnumMap<>(BackpackTier.class);
    private static final Map<UpgradeKind, Item> UPGRADES = new EnumMap<>(UpgradeKind.class);
    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();
    public static BlockEntityType<BackpackBlockEntity> BLOCK_ENTITY;

    private BackpackRegistry() {}
    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath("fabricated_backpacks", path); }

    public static void initialize() {
        for (BackpackTier tier : BackpackTier.values()) {
            var properties = BlockBehaviour.Properties.of()
                    .strength(1.5F).noOcclusion().sound(SoundType.WOOL).mapColor(MapColor.COLOR_BROWN);
            var block = Registry.register(BuiltInRegistries.BLOCK, id(tier.id()), new BackpackBlock(properties));
            BLOCKS.put(tier, block);
            Item.Properties itemProperties = properties(tier.id()).stacksTo(1);
            if (tier == BackpackTier.NETHERITE) itemProperties.fireResistant();
            BACKPACKS.put(tier, register(tier.id(), new BackpackItem(block, itemProperties)));
        }
        for (UpgradeKind kind : UpgradeKind.values()) {
            UPGRADES.put(kind, register(kind.id(), new UpgradeItem(properties(kind.id()).stacksTo(1), kind)));
        }
        register("upgrade_base", new Item(properties("upgrade_base")));
        for (int from = 0; from < 4; from++) {
            for (int to = from + 1; to <= 4; to++) {
                String source = from == 0 ? "starter_tier" : "tier_" + from;
                String path = "stack_upgrade_" + source + "_to_tier_" + to + "_conversion";
                register(path, new Item(properties(path)));
            }
        }
        BLOCK_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("backpack"),
                FabricBlockEntityTypeBuilder.create(BackpackBlockEntity::new, BLOCKS.values().toArray(Block[]::new)).build());
    }

    private static Item.Properties properties(String path) {
        return new Item.Properties();
    }
    private static Item register(String path, Item item) {
        Registry.register(BuiltInRegistries.ITEM, id(path), item);
        ITEMS.put(path, item);
        return item;
    }
    public static Map<String, Item> items() { return Map.copyOf(ITEMS); }
    public static Item item(UpgradeKind kind) { return UPGRADES.get(kind); }
    public static Item item(BackpackTier tier) { return BACKPACKS.get(tier); }
    public static BackpackBlock block(BackpackTier tier) { return BLOCKS.get(tier); }
    public static boolean isBackpack(net.minecraft.world.item.ItemStack stack) { return stack.getItem() instanceof BackpackItem; }
    public static Optional<BackpackTier> tier(net.minecraft.world.item.ItemStack stack) {
        if (!isBackpack(stack)) return Optional.empty();
        return BackpackTier.byId(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
    }
    public static Optional<UpgradeKind> kind(net.minecraft.world.item.ItemStack stack) {
        return stack.getItem() instanceof UpgradeItem item ? Optional.of(item.kind()) : Optional.empty();
    }
}
