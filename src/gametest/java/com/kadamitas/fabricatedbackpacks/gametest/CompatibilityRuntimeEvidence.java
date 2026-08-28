package com.kadamitas.fabricatedbackpacks.gametest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Testmod runtime witness only; it does not certify that any test has passed. */
final class CompatibilityRuntimeEvidence {
    private static final String OUTPUT_PROPERTY = "fabricated.backpacks.compatibilityEvidence";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final List<String> MOD_IDS = List.of(
            "fabricated_backpacks", "fabric-api", "team_reborn_energy", "fabricloader", "cobblemon", "jei");
    private static final List<String> ITEM_IDS = List.of(
            "cobblemon:poke_ball", "cobblemon:great_ball", "cobblemon:ultra_ball");

    private CompatibilityRuntimeEvidence() {}

    static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> writeIfConfigured());
    }

    private static void writeIfConfigured() {
        String configured = System.getProperty(OUTPUT_PROPERTY);
        if (configured == null) return;
        if (configured.isBlank()) throw new IllegalStateException("Compatibility evidence output must not be blank");

        FabricLoader loader = FabricLoader.getInstance();
        JsonObject evidence = new JsonObject();
        evidence.addProperty("schema", 1);
        evidence.addProperty("recorded_at", System.currentTimeMillis());
        evidence.addProperty("pid", ProcessHandle.current().pid());
        evidence.addProperty("minecraft_version", SharedConstants.getCurrentVersion().getName());
        evidence.addProperty("java_feature", Runtime.version().feature());
        evidence.addProperty("environment", loader.getEnvironmentType().name());
        JsonObject versions = new JsonObject();
        for (String id : MOD_IDS) {
            loader.getModContainer(id).ifPresent(mod ->
                    versions.addProperty(id, mod.getMetadata().getVersion().getFriendlyString()));
        }
        evidence.add("mod_versions", versions);
        JsonArray registeredItems = new JsonArray();
        for (String id : ITEM_IDS) {
            if (BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(id))) registeredItems.add(id);
        }
        evidence.add("registered_items", registeredItems);

        Path destination = Path.of(configured).toAbsolutePath().normalize();
        Path temporary = null;
        try {
            Files.createDirectories(destination.getParent());
            temporary = Files.createTempFile(destination.getParent(), ".compatibility-runtime-", ".tmp");
            Files.writeString(temporary, JSON.toJson(evidence) + "\n", StandardCharsets.UTF_8);
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot atomically write compatibility runtime evidence", failure);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException ignored) { /* Preserve the startup failure that prevented publication. */ }
            }
        }
    }
}
