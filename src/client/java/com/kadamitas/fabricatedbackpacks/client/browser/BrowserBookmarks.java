package com.kadamitas.fabricatedbackpacks.client.browser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

/** Client preferences only; bookmarks grant no inventory or recipe permissions. */
final class BrowserBookmarks {
    private static final Logger LOGGER = LoggerFactory.getLogger("fabricated_backpacks/browser");
    private static final int LIMIT = 512;
    private final Set<Identifier> items = new LinkedHashSet<>();
    private final Set<Identifier> recipes = new LinkedHashSet<>();
    private final Path path = FabricLoader.getInstance().getConfigDir().resolve("fabricated-backpacks-browser.json");
    private long revision;

    BrowserBookmarks() {
        if (!Files.isRegularFile(path)) return;
        try {
            if (Files.size(path) > 1_048_576) throw new IOException("Bookmark file is too large");
            JsonObject document = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            if (document.get("schema").getAsInt() != 1) throw new IOException("Unsupported bookmark schema");
            read(document.getAsJsonArray("items"), items);
            read(document.getAsJsonArray("recipes"), recipes);
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not load recipe-browser bookmarks; the existing file was not changed", exception);
        }
    }

    boolean containsItem(Identifier id) { return items.contains(id); }
    boolean containsRecipe(Identifier id) { return recipes.contains(id); }
    long revision() { return revision; }
    boolean toggleItem(Identifier id) { return toggle(items, id); }
    boolean toggleRecipe(Identifier id) { return toggle(recipes, id); }

    private boolean toggle(Set<Identifier> target, Identifier id) {
        if (!target.remove(id)) {
            if (target.size() >= LIMIT) return false;
            target.add(id);
        }
        revision++;
        save();
        return true;
    }

    private static void read(JsonArray values, Set<Identifier> target) {
        if (values == null) return;
        for (var value : values) {
            Identifier id = Identifier.tryParse(value.getAsString());
            if (id != null && target.size() < LIMIT) target.add(id);
        }
    }

    private void save() {
        JsonObject document = new JsonObject();
        document.addProperty("schema", 1);
        JsonArray savedItems = new JsonArray(), savedRecipes = new JsonArray();
        items.forEach(id -> savedItems.add(id.toString()));
        recipes.forEach(id -> savedRecipes.add(id.toString()));
        document.add("items", savedItems);
        document.add("recipes", savedRecipes);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, document + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not save recipe-browser bookmarks", exception);
        }
    }
}
