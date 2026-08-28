package com.kadamitas.fabricatedbackpacks.client.browser;

import com.kadamitas.fabricatedbackpacks.browser.BrowserQuery;
import com.kadamitas.fabricatedbackpacks.browser.BrowserRecipeEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Incremental item/recipe indexing with a six-millisecond work budget per tick. */
final class BrowserClientIndex {
    private static final Logger LOGGER = LoggerFactory.getLogger("fabricated_backpacks/browser");
    private static final long TICK_BUDGET_NANOS = 6_000_000;
    private final List<BrowserItem> items = new ArrayList<>();
    private final Map<ResourceLocation, BrowserItem> itemsById = new HashMap<>();
    private final Map<ResourceLocation, List<BrowserRecipeView>> recipesByResult = new HashMap<>();
    private final Map<ResourceLocation, List<BrowserRecipeView>> recipesByInput = new HashMap<>();
    private final List<BrowserRecipeView> recipes = new ArrayList<>();
    private final ArrayDeque<Item> pendingItems = new ArrayDeque<>();
    private final ArrayDeque<BrowserRecipeEntry> pendingRecipes = new ArrayDeque<>();
    private boolean started;
    private boolean sorted;
    private long version;
    private long buildNanos;
    private long searchNanos;
    private String queryText;
    private long queryVersion = -1;
    private List<BrowserItem> queryResult = List.of();

    long version() { return version; }
    int itemCount() { return items.size(); }
    int recipeCount() { return recipes.size(); }
    boolean building() { return !pendingItems.isEmpty() || !pendingRecipes.isEmpty(); }
    boolean itemsBuilding() { return !pendingItems.isEmpty(); }
    long buildNanos() { return buildNanos; }
    long searchNanos() { return searchNanos; }
    List<BrowserRecipeView> allRecipes() { return List.copyOf(recipes); }

    void begin(Minecraft client) {
        if (started || client.level == null) return;
        started = true;
        for (Item item : BuiltInRegistries.ITEM) if (item != Items.AIR && item.isEnabled(client.level.enabledFeatures())) pendingItems.add(item);
    }

    void addRecipes(List<BrowserRecipeEntry> entries) { pendingRecipes.addAll(entries); }

    void tick(Minecraft client) {
        if (!started || client.level == null) return;
        long start = System.nanoTime();
        int processed = 0;
        while (processed < 128 && System.nanoTime() - start < TICK_BUDGET_NANOS && !pendingItems.isEmpty()) {
            Item item = pendingItems.removeFirst();
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            ItemStack stack = item.getDefaultInstance();
            String name = stack.getHoverName().getString();
            String tooltip = String.join(" ", Screen.getTooltipFromItem(client, stack).stream().map(component -> component.getString()).toList());
            BrowserItem entry = new BrowserItem(id, stack, new BrowserQuery.SearchText(name + " " + id, id.getNamespace(), tooltip));
            items.add(entry);
            itemsById.put(id, entry);
            processed++;
        }
        if (pendingItems.isEmpty() && !sorted) {
            items.sort(Comparator.comparing(entry -> entry.text().nameAndId()));
            sorted = true;
            LOGGER.info("Recipe browser cached {} searchable item names and tooltips", items.size());
        }
        while (processed < 160 && System.nanoTime() - start < TICK_BUDGET_NANOS && !pendingRecipes.isEmpty()) {
            BrowserRecipeEntry source = pendingRecipes.removeFirst();
            try {
                BrowserRecipeView recipe = resolve(source);
                recipes.add(recipe);
                for (ResourceLocation id : recipe.resultIds()) recipesByResult.computeIfAbsent(id, ignored -> new ArrayList<>()).add(recipe);
                for (ResourceLocation id : recipe.ingredientIds()) recipesByInput.computeIfAbsent(id, ignored -> new ArrayList<>()).add(recipe);
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not resolve recipe browser display {}", source.recipe(), exception);
            }
            processed++;
        }
        if (processed > 0) {
            version++;
            buildNanos += System.nanoTime() - start;
        }
    }

    List<BrowserItem> search(String query) {
        if (query.equals(queryText) && queryVersion == version) return queryResult;
        long started = System.nanoTime();
        BrowserQuery parsed = BrowserQuery.parse(query);
        queryResult = items.stream().filter(item -> parsed.matches(item.text)).toList();
        queryText = query;
        queryVersion = version;
        searchNanos = System.nanoTime() - started;
        return queryResult;
    }

    ItemStack item(ResourceLocation id) {
        BrowserItem item = itemsById.get(id);
        if (item != null) return item.stack;
        return BuiltInRegistries.ITEM.getOptional(id).map(Item::getDefaultInstance).orElse(ItemStack.EMPTY);
    }

    List<BrowserRecipeView> recipes(ResourceLocation item, boolean uses) {
        return (uses ? recipesByInput : recipesByResult).getOrDefault(item, List.of());
    }

    private BrowserRecipeView resolve(BrowserRecipeEntry source) {
        if (source.columns() < 1 || source.columns() > 9 || source.rows() < 0 || source.rows() > 9
                || source.ingredients().size() > 81) throw new IllegalArgumentException("Unsupported recipe display dimensions");
        return new BrowserRecipeView(source, BrowserRecipeView.Layout.valueOf(source.layout().name()), source.columns(), source.rows(),
                source.ingredients().stream().map(group -> group.stream().map(ItemStack::copy).toList()).toList(),
                source.fuel().stream().map(ItemStack::copy).toList(), source.results().stream().map(ItemStack::copy).toList(),
                source.stations().stream().map(ItemStack::copy).toList(), source.duration(), source.experience());
    }

    record BrowserItem(ResourceLocation id, ItemStack stack, BrowserQuery.SearchText text) {}
}
