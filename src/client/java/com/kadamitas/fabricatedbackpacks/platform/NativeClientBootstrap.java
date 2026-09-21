package com.kadamitas.fabricatedbackpacks.platform;

import com.kadamitas.fabricatedbackpacks.client.FabricatedBackpacksClient;
import net.neoforged.bus.api.IEventBus;

/** Invoked only on the physical client by the common mod constructor. */
public final class NativeClientBootstrap {
    private static boolean registered;

    private NativeClientBootstrap() {}

    public static void register(IEventBus modBus) {
        if (registered) return;
        registered = true;
        FabricatedBackpacksClient.initialize(modBus);
    }
}
