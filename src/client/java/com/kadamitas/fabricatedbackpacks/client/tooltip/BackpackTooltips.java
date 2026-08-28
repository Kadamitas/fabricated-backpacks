package com.kadamitas.fabricatedbackpacks.client.tooltip;

import com.kadamitas.fabricatedbackpacks.item.BackpackTooltip;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;

/** Installs the client half without introducing client classes into common item code. */
public final class BackpackTooltips {
    private static boolean initialized;

    private BackpackTooltips() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ClientTooltipComponentCallback.EVENT.register(component ->
                component instanceof BackpackTooltip backpack ? new BackpackContentsTooltip(backpack) : null);
    }
}
