package com.kadamitas.fabricatedbackpacks.testplatform.impl.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.tags.TagNetworkSerialization;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;

/**
 * Test-only process isolation for the upstream harness's embedded dedicated server.
 * NeoForge's client frozen-registry remap re-freezes shared static registries, which
 * otherwise replaces the live server's datapack tags with registration-time tags.
 * Keep the real loaded bindings, then transmit them through the normal tag packet
 * after the real frozen-registry handshake, before configuration finishes.
 */
public final class EmbeddedServerTagIsolation {
    private static final ConfigurationTask.Type TYPE = new ConfigurationTask.Type("fabricated_backpacks_tests:embedded_server_tags");
    private static volatile Snapshot active;

    private EmbeddedServerTagIsolation() {}

    public static void capture(MinecraftServer server, UUID hostProfile) {
        if (!server.isSameThread()) throw new IllegalStateException("Capture live server tags on its own thread");
        List<Registry.PendingTags<?>> bindings = new ArrayList<>();
        for (Registry<?> registry : BuiltInRegistries.REGISTRY) bindings.add(captureRegistry(registry));
        active = new Snapshot(server, hostProfile, List.copyOf(bindings));
    }

    private static <T> Registry.PendingTags<T> captureRegistry(Registry<T> registry) {
        Map<TagKey<T>, List<Holder<T>>> tags = new LinkedHashMap<>();
        registry.getTags().forEach(tag -> tags.put(tag.key(), tag.stream().toList()));
        return registry.prepareTagReload(new TagLoader.LoadResult<>(registry.key(), Map.copyOf(tags)));
    }

    public static void configure(RegisterConfigurationTasksEvent event) {
        Snapshot snapshot = active;
        if (snapshot == null || !(event.getListener() instanceof ServerConfigurationPacketListenerImpl listener)
                || !listener.getOwner().id().equals(snapshot.hostProfile())) return;
        event.register(new ConfigurationTask() {
            @Override public Type type() { return TYPE; }
            @Override public void start(Consumer<Packet<?>> sender) {
                snapshot.server().execute(() -> {
                    if (active != snapshot)
                        throw new IllegalStateException("Embedded host registry snapshot does not belong to the running server");
                    snapshot.bindings().forEach(Registry.PendingTags::apply);
                    sender.accept(new ClientboundUpdateTagsPacket(TagNetworkSerialization.serializeTagsToNetwork(snapshot.server().registries())));
                    // This server-only task needs no custom payload or fabricated client acknowledgment.
                    // All native frozen-registry acknowledgments have already completed normally.
                    listener.finishCurrentTask(TYPE);
                });
            }
        });
    }

    public static void clear(MinecraftServer server) {
        Snapshot snapshot = active;
        if (snapshot != null && snapshot.server() == server) active = null;
    }

    private record Snapshot(MinecraftServer server, UUID hostProfile, List<Registry.PendingTags<?>> bindings) {}
}
