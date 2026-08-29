package com.kadamitas.fabricatedbackpacks.automation.engine;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;

public final class SteamEngineComponents {
    public static final DataComponentType<SteamEngineState> STATE = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            Identifier.fromNamespaceAndPath("fabricated_backpacks", "steam_engine_state"),
            DataComponentType.<SteamEngineState>builder().persistent(SteamEngineState.CODEC)
                    .networkSynchronized(ByteBufCodecs.fromCodec(SteamEngineState.CODEC)).cacheEncoding().build());
    public static final DataComponentType<SteamEngineSides> SIDES = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
            Identifier.fromNamespaceAndPath("fabricated_backpacks", "steam_engine_sides"),
            DataComponentType.<SteamEngineSides>builder().persistent(SteamEngineSides.CODEC)
                    .networkSynchronized(ByteBufCodecs.fromCodec(SteamEngineSides.CODEC)).cacheEncoding().build());

    private SteamEngineComponents() { }
    public static void initialize() { }
}
