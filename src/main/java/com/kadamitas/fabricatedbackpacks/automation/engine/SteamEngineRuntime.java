package com.kadamitas.fabricatedbackpacks.automation.engine;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.platform.transfer.FluidStorage;
import com.kadamitas.fabricatedbackpacks.platform.transfer.ItemStorage;
import com.kadamitas.fabricatedbackpacks.platform.transfer.EnergyStorage;

public final class SteamEngineRuntime {
    private static boolean initialized;
    private SteamEngineRuntime() { }
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ItemStorage.SIDED.registerForBlockEntity(SteamEngineBlockEntity::itemStorage, AutomationRegistry.STEAM_ENGINE_ENTITY);
        FluidStorage.SIDED.registerForBlockEntity(SteamEngineBlockEntity::fluidStorage, AutomationRegistry.STEAM_ENGINE_ENTITY);
        EnergyStorage.SIDED.registerForBlockEntity(SteamEngineBlockEntity::energyStorage, AutomationRegistry.STEAM_ENGINE_ENTITY);
    }
}
