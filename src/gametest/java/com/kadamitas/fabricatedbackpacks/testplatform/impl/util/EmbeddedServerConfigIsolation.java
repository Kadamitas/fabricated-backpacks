package com.kadamitas.fabricatedbackpacks.testplatform.impl.util;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.loading.FMLPaths;

/** Restores the embedded server's real file-backed configs after its host receives them over TCP. */
public final class EmbeddedServerConfigIsolation {
    private EmbeddedServerConfigIsolation() {}

    public static Snapshot capture(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Capture server configs on its own thread");
        List<Binding> bindings = ModConfigs.getConfigSet(ModConfig.Type.SERVER).stream().map(config ->
                new Binding(config, Objects.requireNonNull(config.getFullPath(), "Server config must be file-backed"),
                        Objects.requireNonNull(config.getLoadedConfig(), "Server config must be loaded"))).toList();
        return new Snapshot(server, bindings);
    }

    public static final class Snapshot {
        private final MinecraftServer server;
        private final List<Binding> bindings;

        private Snapshot(MinecraftServer server, List<Binding> bindings) {
            this.server = server;
            this.bindings = bindings;
        }

        public void restore() {
            if (!server.isSameThread()) throw new IllegalStateException("Restore server configs on its own thread");
            for (Binding binding : bindings) binding.checkValues();
            // The real host has already received/accepted the normal config payloads. In this
            // test-only shared JVM that replaces the server's LoadedConfig with a pathless
            // client copy. Reopen the exact server files through the public native lifecycle
            // API, so the independent guest still receives the ordinary native config sync.
            ConfigTracker.INSTANCE.loadConfigs(ModConfig.Type.SERVER, FMLPaths.CONFIGDIR.get(),
                    server.getWorldPath(new LevelResource("serverconfig")));
            for (Binding binding : bindings) {
                if (!binding.path().equals(binding.config().getFullPath()))
                    throw new AssertionError("Embedded server config path changed: " + binding.config().getFileName());
                binding.checkValues();
            }
        }
    }

    private record Binding(ModConfig config, Path path, IConfigSpec.ILoadedConfig original) {
        void checkValues() {
            IConfigSpec.ILoadedConfig current = config.getLoadedConfig();
            if (current == null || !original.config().valueMap().equals(current.config().valueMap()))
                throw new AssertionError("Embedded server config values changed: " + config.getFileName());
        }
    }
}
