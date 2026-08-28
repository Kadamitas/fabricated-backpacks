package com.kadamitas.fabricatedbackpacks.admin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Local names live in saved data; namespaced references resolve against the current datapacks. */
public final class BackpackTemplates {
    public static final String DIRECTORY = "backpack_templates";
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private BackpackTemplates() { }

    public static List<String> names(MinecraftServer server) {
        List<String> names = new ArrayList<>(AdminSavedData.of(server).templateNames());
        server.getResourceManager().listResources(DIRECTORY, path -> path.getPath().endsWith(".json"))
                .keySet().stream().sorted().map(path -> path.getNamespace() + ":"
                        + path.getPath().substring(DIRECTORY.length() + 1, path.getPath().length() - 5)).forEach(names::add);
        return List.copyOf(names);
    }
    public static Optional<WholeBagTemplate> load(MinecraftServer server, String reference) throws IOException {
        if (AdminNames.isLocal(reference)) return AdminSavedData.of(server).template(reference);
        ResourceLocation id = ResourceLocation.tryParse(reference);
        if (id == null || !reference.contains(":")) throw new IllegalArgumentException("Invalid template reference");
        return read(server.getResourceManager(), server.registryAccess(), id);
    }
    public static Optional<WholeBagTemplate> read(ResourceManager resources, HolderLookup.Provider registries, ResourceLocation id) throws IOException {
        var resource = resources.getResource(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), DIRECTORY + "/" + id.getPath() + ".json"));
        if (resource.isEmpty()) return Optional.empty();
        try (var input = resource.get().open()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Backpack template exceeds 4 MiB");
            return Optional.of(decode(registries, new String(bytes, StandardCharsets.UTF_8)));
        }
    }
    public static String encode(HolderLookup.Provider registries, WholeBagTemplate template) {
        String json = JSON.toJson(WholeBagTemplate.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), template).getOrThrow());
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Backpack template exceeds 4 MiB");
        return json;
    }
    public static WholeBagTemplate decode(HolderLookup.Provider registries, String json) {
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IllegalArgumentException("Backpack template exceeds 4 MiB");
        return WholeBagTemplate.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), JsonParser.parseString(json)).getOrThrow();
    }

    /** Export only creates a new directory and files. Neither existing packs nor templates are replaced. */
    public static Path export(MinecraftServer server, String reference, String exportName) throws IOException {
        AdminNames.local(exportName);
        WholeBagTemplate template = load(server, reference).orElseThrow(() -> new IllegalArgumentException("Template not found: " + reference));
        String encoded = encode(server.registryAccess(), template);
        var format = SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA);
        var metadata = new PackMetadataSection(Component.literal("Fabricated Backpacks whole-backpack template"), format, java.util.Optional.of(new InclusiveRange<>(format, format)));
        JsonObject manifest = new JsonObject();
        manifest.add("pack", PackMetadataSection.CODEC.encodeStart(JsonOps.INSTANCE, metadata).getOrThrow());

        Path root = server.getWorldPath(LevelResource.DATAPACK_DIR).toAbsolutePath().normalize();
        rejectSymbolicAncestors(root);
        Files.createDirectories(root);
        Path pack = root.resolve("fabricated_backpacks_template_" + exportName).normalize();
        if (!pack.startsWith(root) || pack.equals(root)) throw new IOException("Export resolved outside the datapack directory");
        // CREATE_NEW directory semantics also refuse a dangling link or a concurrent exporter.
        Files.createDirectory(pack);
        Path content = pack.resolve("data/fabricated_backpacks/" + DIRECTORY);
        Files.createDirectories(content);
        Files.writeString(content.resolve(exportName + ".json"), encoded, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(pack.resolve("pack.mcmeta"), JSON.toJson(manifest), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return pack;
    }
    private static void rejectSymbolicAncestors(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent())
            if (Files.isSymbolicLink(current) || Files.exists(current, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("The export directory must contain only ordinary directories");
    }
}
