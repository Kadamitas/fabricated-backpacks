package com.kadamitas.fabricatedbackpacks;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackRuntime;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
import com.kadamitas.fabricatedbackpacks.recipe.BackpackRecipes;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FabricatedBackpacks implements ModInitializer {
    public static final String MOD_ID = "fabricated_backpacks";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    @Override public void onInitialize() {
        com.kadamitas.fabricatedbackpacks.config.BackpackConfig.initialize();
        BagComponents.initialize();
        BackpackRegistry.initialize();
        com.kadamitas.fabricatedbackpacks.world.WorldBackpacks.initialize();
        com.kadamitas.fabricatedbackpacks.settings.SettingsRuntime.initialize();
        com.kadamitas.fabricatedbackpacks.admin.BackpackAdmin.initialize();
        BackpackRecipes.initialize();
        BackpackMenus.initialize();
        BackpackEquipment.initialize();
        BackpackRuntime.initialize();
        com.kadamitas.fabricatedbackpacks.upgrade.ToolRules.initialize();
        com.kadamitas.fabricatedbackpacks.network.BackpackNetworking.initialize();
        com.kadamitas.fabricatedbackpacks.browser.RecipeBrowserServer.initialize();
        com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime.register();
        AutomationRegistry.initialize();
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, BackpackRegistry.id("backpacks"),
                CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0).title(Component.literal("Fabricated Backpacks"))
                        .icon(() -> new ItemStack(BackpackRegistry.item(BackpackTier.DIAMOND)))
                        .displayItems((parameters, output) -> java.util.stream.Stream.concat(
                                BackpackRegistry.items().entrySet().stream(), AutomationRegistry.items().entrySet().stream())
                                .sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> output.accept(entry.getValue()))).build());
        LOGGER.info("Fabricated Backpacks initialized with {} registered items", BackpackRegistry.items().size() + AutomationRegistry.items().size());
    }
}
