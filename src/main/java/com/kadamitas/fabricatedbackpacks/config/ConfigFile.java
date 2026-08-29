package com.kadamitas.fabricatedbackpacks.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Partial files inherit defaults; invalid files remain untouched and never partially apply. */
public final class ConfigFile {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int MAX_BYTES = 1_048_576;
    private ConfigFile() { }

    public static String encode(ServerConfig settings) { return JSON.toJson(settings) + System.lineSeparator(); }

    public static ServerConfig decode(String text) {
        if (text.length() > MAX_BYTES) throw new IllegalArgumentException("Configuration is larger than 1 MiB");
        JsonElement parsed = JsonParser.parseString(text);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Configuration must be a JSON object");
        JsonObject supplied = migrate(parsed.getAsJsonObject());
        JsonObject merged = JSON.toJsonTree(ServerConfig.defaults()).getAsJsonObject();
        merge(merged, supplied, "");
        return JSON.fromJson(merged, ServerConfig.class);
    }

    /**
     * Format 1 shipped with twelve advanced-jukebox slots. Treat that exact historical
     * value as its old default while preserving every custom size. Format 2 makes an
     * explicit twelve-slot choice stable instead of repeatedly guessing user intent.
     */
    private static JsonObject migrate(JsonObject source) {
        JsonObject migrated = source.deepCopy();
        JsonElement format = migrated.get("format");
        if (format == null || !format.isJsonPrimitive() || !format.getAsJsonPrimitive().isNumber()
                || format.getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) != 0) return migrated;
        migrated.addProperty("format", ServerConfig.CURRENT_FORMAT);
        JsonElement upgrades = migrated.get("upgrades");
        if (upgrades == null || !upgrades.isJsonObject()) return migrated;
        JsonElement jukebox = upgrades.getAsJsonObject().get("jukebox");
        if (jukebox == null || !jukebox.isJsonObject()) return migrated;
        JsonElement size = jukebox.getAsJsonObject().get("size");
        if (size != null && size.isJsonPrimitive() && size.getAsJsonPrimitive().isNumber()
                && size.getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(12)) == 0) {
            jukebox.getAsJsonObject().addProperty("size", 24);
        }
        return migrated;
    }

    private static void merge(JsonObject defaults, JsonObject supplied, String prefix) {
        for (var entry : supplied.entrySet()) {
            String name = entry.getKey();
            String path = prefix + name;
            if (!defaults.has(name)) throw new IllegalArgumentException("Unknown setting: " + path);
            if (entry.getValue().isJsonNull()) throw new IllegalArgumentException("Setting cannot be null: " + path);
            JsonElement before = defaults.get(name);
            // These are complete configurable maps: an empty map intentionally disables their entries.
            boolean dynamicMap = path.equals("carriers.lootTables") || path.equals("carriers.colors")
                    || path.equals("upgrades.compacting.itemOverrides");
            if (!dynamicMap && before.isJsonObject() && entry.getValue().isJsonObject()) {
                merge(before.getAsJsonObject(), entry.getValue().getAsJsonObject(), path + ".");
            } else defaults.add(name, entry.getValue().deepCopy());
        }
    }

    public static ServerConfig loadOrCreate(Path path) throws IOException {
        if (!Files.exists(path)) {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            // CREATE_NEW refuses to overwrite a file concurrently created by an administrator.
            try { Files.writeString(path, encode(ServerConfig.defaults()), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW); }
            catch (java.nio.file.FileAlreadyExistsException raced) { /* Read the winner below. */ }
        }
        if (Files.size(path) > MAX_BYTES) throw new IOException("Configuration is larger than 1 MiB: " + path);
        return decode(Files.readString(path, StandardCharsets.UTF_8));
    }
}
