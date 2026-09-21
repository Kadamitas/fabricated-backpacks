package com.kadamitas.fabricatedbackpacks.client.tooltip;

import com.kadamitas.fabricatedbackpacks.item.BackpackTooltip;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;

/** Installs the client half without introducing client classes into common item code. */
public final class BackpackTooltips {
    private static boolean initialized;

    private BackpackTooltips() {}

    public static void initialize(IEventBus modBus) {
        if (initialized) return;
        initialized = true;
        modBus.addListener((RegisterClientTooltipComponentFactoriesEvent event) ->
                event.register(BackpackTooltip.class, BackpackContentsTooltip::new));
    }
}
