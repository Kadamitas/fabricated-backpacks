package com.kadamitas.fabricatedbackpacks.automation;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlock;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitItem;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMenus;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitNetworks;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitWrenchItem;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineBlock;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineComponents;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineMenus;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineRuntime;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

import java.util.LinkedHashMap;
import java.util.Map;

/** Native automation is independent of backpack ownership; interoperability uses public sided APIs. */
public final class AutomationRegistry {
    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();
    public static ConduitBundleBlock CONDUIT_BUNDLE;
    public static SteamEngineBlock STEAM_ENGINE;
    public static BlockEntityType<ConduitBundleBlockEntity> CONDUIT_BUNDLE_ENTITY;
    public static BlockEntityType<SteamEngineBlockEntity> STEAM_ENGINE_ENTITY;
    public static Item ITEM_CONDUIT, FLUID_CONDUIT, ENERGY_CONDUIT, STEAM_ENGINE_ITEM, CONDUIT_WRENCH;

    private AutomationRegistry() {}

    public static void initialize() {
        SteamEngineComponents.initialize();
        CONDUIT_BUNDLE = Registry.register(BuiltInRegistries.BLOCK, BackpackRegistry.id("conduit_bundle"),
                new ConduitBundleBlock(blockProperties("conduit_bundle").strength(.4F).dynamicShape()));
        STEAM_ENGINE = Registry.register(BuiltInRegistries.BLOCK, BackpackRegistry.id("steam_engine"),
                new SteamEngineBlock(blockProperties("steam_engine").strength(3.5F)));
        CONDUIT_BUNDLE_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, BackpackRegistry.id("conduit_bundle"),
                FabricBlockEntityTypeBuilder.create(ConduitBundleBlockEntity::new, CONDUIT_BUNDLE).build());
        STEAM_ENGINE_ENTITY = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, BackpackRegistry.id("steam_engine"),
                FabricBlockEntityTypeBuilder.create(SteamEngineBlockEntity::new, STEAM_ENGINE).build());
        ITEM_CONDUIT = register("item_conduit", new ConduitItem(CONDUIT_BUNDLE, itemProperties("item_conduit"), ConduitKind.ITEM));
        FLUID_CONDUIT = register("fluid_conduit", new ConduitItem(CONDUIT_BUNDLE, itemProperties("fluid_conduit"), ConduitKind.FLUID));
        ENERGY_CONDUIT = register("energy_conduit", new ConduitItem(CONDUIT_BUNDLE, itemProperties("energy_conduit"), ConduitKind.ENERGY));
        STEAM_ENGINE_ITEM = register("steam_engine", new AutomationBlockItem(STEAM_ENGINE, itemProperties("steam_engine").stacksTo(1)));
        CONDUIT_WRENCH = register("conduit_wrench", new ConduitWrenchItem(itemProperties("conduit_wrench").stacksTo(1)));
        ConduitMenus.initialize();
        SteamEngineMenus.initialize();
        ConduitNetworks.initialize();
        SteamEngineRuntime.initialize();
    }

    private static BlockBehaviour.Properties blockProperties(String path) {
        return BlockBehaviour.Properties.of()
                .noOcclusion().sound(SoundType.METAL).mapColor(MapColor.METAL);
    }

    private static Item.Properties itemProperties(String path) {
        return new Item.Properties();
    }

    private static Item register(String path, Item item) {
        Registry.register(BuiltInRegistries.ITEM, BackpackRegistry.id(path), item);
        ITEMS.put(path, item);
        return item;
    }

    public static Item conduit(ConduitKind kind) {
        return switch (kind) { case ITEM -> ITEM_CONDUIT; case FLUID -> FLUID_CONDUIT; case ENERGY -> ENERGY_CONDUIT; };
    }

    public static Map<String, Item> items() { return Map.copyOf(ITEMS); }
}
