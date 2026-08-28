package com.kadamitas.fabricatedbackpacks.config;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;

/** Server startup configuration. Clients obtain authoritative geometry in backpack components. */
public final class BackpackConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("FabricatedBackpacks/Config");
    private static volatile ServerConfig current = ServerConfig.defaults();
    private BackpackConfig() { }
    public static ServerConfig get() { return current; }

    public static void initialize() { load(FabricLoader.getInstance().getConfigDir().resolve("fabricated_backpacks.json")); }

    /** A failed load keeps the last complete configuration and leaves the administrator's file intact. */
    public static boolean load(Path file) {
        try {
            current = ConfigFile.loadOrCreate(file);
            return true;
        } catch (Exception failure) {
            LOGGER.error("Cannot read {}. Keeping the previous configuration; fix the file and restart.", file, failure);
            return false;
        }
    }

    /** Embedding/server test hook. Invoke before constructing inventories, not while menus are open. */
    public static void configure(ServerConfig settings) { current = Objects.requireNonNull(settings); }
}
