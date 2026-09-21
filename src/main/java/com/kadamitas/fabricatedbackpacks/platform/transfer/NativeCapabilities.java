package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

public final class NativeCapabilities {
    private static final List<Consumer<RegisterCapabilitiesEvent>> REGISTRATIONS = new ArrayList<>();
    private NativeCapabilities() {}
    static void add(Consumer<RegisterCapabilitiesEvent> registration) { REGISTRATIONS.add(registration); }
    public static void initialize(IEventBus modBus) { modBus.addListener((RegisterCapabilitiesEvent event) -> REGISTRATIONS.forEach(registration -> registration.accept(event))); }
}
