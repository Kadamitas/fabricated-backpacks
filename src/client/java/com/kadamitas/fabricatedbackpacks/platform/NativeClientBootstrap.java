package com.kadamitas.fabricatedbackpacks.platform;

import com.kadamitas.fabricatedbackpacks.client.FabricatedBackpacksClient;
import net.minecraftforge.eventbus.api.bus.BusGroup;

/** Invoked only on the physical client by the common mod constructor. */
public final class NativeClientBootstrap {
    private static boolean registered;

    private NativeClientBootstrap() {}

    public static void register(BusGroup modBus) {
        if (registered) return;
        registered = true;
        FabricatedBackpacksClient.initialize(modBus);
    }
}
