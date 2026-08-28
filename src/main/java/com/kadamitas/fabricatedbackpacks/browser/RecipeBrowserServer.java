package com.kadamitas.fabricatedbackpacks.browser;

import com.kadamitas.fabricatedbackpacks.menu.WorkstationMenus;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.armortrim.TrimMaterials;
import net.minecraft.world.item.armortrim.TrimPatterns;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Server-owned catalog and transfer boundary. No item data is accepted from clients. */
public final class RecipeBrowserServer {
    private static final Logger LOGGER = LoggerFactory.getLogger("fabricated_backpacks/browser");
    private static final AtomicLong NEXT_EPOCH = new AtomicLong(1);
    private static final Map<MinecraftServer, Catalog> CATALOGS = new HashMap<>();
    private static final Map<UUID, RequestWindow> REQUESTS = new HashMap<>();
    private static final int MAX_TRIM_ASSEMBLIES = 4_096;
    private static boolean initialized;

    private RecipeBrowserServer() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        PayloadTypeRegistry.playC2S().register(BrowserCatalogRequest.TYPE, BrowserCatalogRequest.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(BrowserTransferRequest.TYPE, BrowserTransferRequest.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(BrowserContextRequest.TYPE, BrowserContextRequest.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(BrowserCatalogPage.TYPE, BrowserCatalogPage.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(BrowserCatalogInvalidated.TYPE, BrowserCatalogInvalidated.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(BrowserTransferResult.TYPE, BrowserTransferResult.STREAM_CODEC);
        PayloadTypeRegistry.playS2C().register(BrowserContext.TYPE, BrowserContext.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(BrowserCatalogRequest.TYPE, (request, context) -> sendPage(context.player(), request));
        ServerPlayNetworking.registerGlobalReceiver(BrowserTransferRequest.TYPE, (request, context) -> transfer(context.player(), request));
        ServerPlayNetworking.registerGlobalReceiver(BrowserContextRequest.TYPE, (request, context) -> sendContext(context.player(), request));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
            if (!success) return;
            CATALOGS.remove(server);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (ServerPlayNetworking.canSend(player, BrowserCatalogInvalidated.TYPE)) ServerPlayNetworking.send(player, BrowserCatalogInvalidated.INSTANCE);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            CATALOGS.remove(server);
            REQUESTS.clear();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((connection, server) -> REQUESTS.remove(connection.player.getUUID()));
    }

    private static void sendContext(ServerPlayer player, BrowserContextRequest request) {
        if (!allowRequest(player) || !ServerPlayNetworking.canSend(player, BrowserContext.TYPE)) return;
        BrowserWorkstation workstation = player.containerMenu.containerId == request.containerId()
                ? WorkstationMenus.transferContext(player) : BrowserWorkstation.NONE;
        ServerPlayNetworking.send(player, new BrowserContext(request.containerId(), workstation,
                player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING)));
    }

    private static void sendPage(ServerPlayer player, BrowserCatalogRequest request) {
        if (!player.isAlive() || !allowRequest(player) || !ServerPlayNetworking.canSend(player, BrowserCatalogPage.TYPE)) return;
        MinecraftServer server = player.serverLevel().getServer();
        Catalog catalog = CATALOGS.computeIfAbsent(server, RecipeBrowserServer::buildCatalog);
        int offset = request.epoch() == catalog.epoch ? request.offset() : 0;
        if (offset > catalog.entries.size()) return;
        int end = Math.min(catalog.entries.size(), offset + BrowserCatalogPage.PAGE_SIZE);
        List<BrowserRecipeEntry> page = new ArrayList<>(end - offset);
        for (int index = offset; index < end; index++) {
            SourceEntry entry = catalog.entries.get(index);
            boolean unlocked = entry.recipe.value().isSpecial() || player.getRecipeBook().contains(entry.recipe.id());
            page.add(entry.presentation.withUnlocked(unlocked));
        }
        ServerPlayNetworking.send(player, new BrowserCatalogPage(catalog.epoch, offset, catalog.entries.size(), catalog.undisplayed,
                catalog.truncated, catalog.buildNanos, page));
    }

    private static void transfer(ServerPlayer player, BrowserTransferRequest request) {
        if (!allowRequest(player)) return;
        if (!player.isAlive() || player.isSpectator() || player.containerMenu.containerId != request.containerId()
                || !player.containerMenu.stillValid(player) || !player.containerMenu.getCarried().isEmpty()) {
            report(player, request, false, "browser.fabricated_backpacks.invalid_menu");
            return;
        }
        Catalog catalog = CATALOGS.get(player.serverLevel().getServer());
        if (catalog == null || catalog.epoch != request.epoch() || !catalog.recipeIds.contains(request.recipe())) {
            report(player, request, false, "browser.fabricated_backpacks.stale_recipe");
            if (ServerPlayNetworking.canSend(player, BrowserCatalogInvalidated.TYPE)) ServerPlayNetworking.send(player, BrowserCatalogInvalidated.INSTANCE);
            return;
        }
        ResourceLocation key = request.recipe();
        var current = player.serverLevel().getServer().getRecipeManager().byKey(key);
        if (current.isEmpty() || player.serverLevel().getGameRules().getBoolean(GameRules.RULE_LIMITED_CRAFTING)
                && !current.get().value().isSpecial() && !player.getRecipeBook().contains(key)) {
            report(player, request, false, "browser.fabricated_backpacks.locked");
            return;
        }
        boolean transferred = WorkstationMenus.transfer(player, request.recipe(), request.maximum());
        report(player, request, transferred, transferred ? "browser.fabricated_backpacks.transfer_success" : "browser.fabricated_backpacks.transfer_failed");
    }

    private static void report(ServerPlayer player, BrowserTransferRequest request, boolean success, String key) {
        player.sendSystemMessage(Component.translatable(key));
        if (ServerPlayNetworking.canSend(player, BrowserTransferResult.TYPE)) ServerPlayNetworking.send(player, new BrowserTransferResult(request.requestId(), success, key));
    }

    private static boolean allowRequest(ServerPlayer player) {
        long tick = player.serverLevel().getServer().getTickCount();
        RequestWindow window = REQUESTS.get(player.getUUID());
        if (window == null || window.tick != tick) {
            REQUESTS.put(player.getUUID(), new RequestWindow(tick, 1));
            return true;
        }
        if (window.count >= 8) return false;
        REQUESTS.put(player.getUUID(), new RequestWindow(tick, window.count + 1));
        return true;
    }

    private static Catalog buildCatalog(MinecraftServer server) {
        long started = System.nanoTime();
        List<SourceEntry> entries = new ArrayList<>();
        Set<ResourceLocation> ids = new HashSet<>();
        int undisplayed = 0;
        boolean truncated = false;
        List<RecipeHolder<?>> recipes = new ArrayList<>(server.getRecipeManager().getRecipes());
        recipes.sort(Comparator.comparing(holder -> holder.id().toString()));
        // Include Fabric's registered fuels and resolve their stable order only once per catalog.
        // The extra sentinel entry makes oversized fuel sets explicitly unsupported, never truncated.
        List<ItemStack> fuels = AbstractFurnaceBlockEntity.getFuel().keySet().stream()
                .sorted(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item).toString()))
                .limit(BrowserRecipeEntry.MAX_FUEL_OPTIONS + 1L).map(ItemStack::new).toList();
        for (RecipeHolder<?> holder : recipes) {
            if (entries.size() >= BrowserCatalogPage.MAX_ENTRIES) { truncated = true; break; }
            ResourceLocation category = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
            BrowserRecipeEntry probe;
            try {
                probe = presentation(holder, category, server, fuels);
            } catch (IllegalArgumentException exception) {
                undisplayed++;
                continue;
            }
            if (probe.results().isEmpty()) { undisplayed++; continue; }
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
            boolean withinBudget;
            try {
                probe.write(buffer);
                withinBudget = buffer.readableBytes() <= 12_000;
            } finally {
                buffer.release();
            }
            if (!withinBudget) { undisplayed++; continue; }
            entries.add(new SourceEntry(holder, probe));
            ids.add(holder.id());
        }
        long elapsed = System.nanoTime() - started;
        LOGGER.info("Recipe browser indexed {} displays ({} without a bounded display) in {} ms", entries.size(), undisplayed, elapsed / 1_000_000.0);
        return new Catalog(NEXT_EPOCH.getAndIncrement(), List.copyOf(entries), Set.copyOf(ids), undisplayed, truncated, elapsed);
    }

    private static BrowserRecipeEntry presentation(RecipeHolder<?> holder, ResourceLocation category, MinecraftServer server,
                                                   List<ItemStack> fuels) {
        Recipe<?> recipe = holder.value();
        List<List<ItemStack>> ingredients = recipe.getIngredients().stream()
                .map(ingredient -> java.util.Arrays.stream(ingredient.getItems()).map(ItemStack::copy).toList()).toList();
        BrowserRecipeEntry.Layout layout = BrowserRecipeEntry.Layout.GENERIC;
        int columns = Math.min(3, Math.max(1, ingredients.size()));
        int rows = Math.max(1, (ingredients.size() + columns - 1) / columns);
        int duration = 0;
        float experience = 0;
        List<ItemStack> fuel = List.of();
        if (recipe instanceof CraftingRecipe) layout = BrowserRecipeEntry.Layout.CRAFTING;
        if (recipe instanceof ShapedRecipe shaped) {
            columns = shaped.getWidth();
            rows = shaped.getHeight();
        } else if (recipe instanceof AbstractCookingRecipe cooking) {
            layout = BrowserRecipeEntry.Layout.FURNACE;
            duration = cooking.getCookingTime();
            experience = cooking.getExperience();
            fuel = fuels;
        } else if (recipe instanceof StonecutterRecipe) {
            layout = BrowserRecipeEntry.Layout.STONECUTTING;
        } else if (recipe instanceof SmithingRecipe smithing) {
            layout = BrowserRecipeEntry.Layout.SMITHING;
            // The 1.21.1 smithing interface exposes predicates, not getIngredients().
            // Resolve its registered item examples once per catalog/reload.
            ingredients = List.of(smithingOptions(smithing::isTemplateIngredient),
                    smithingOptions(smithing::isBaseIngredient), smithingOptions(smithing::isAdditionIngredient));
            columns = 3;
            rows = 1;
        }
        List<ItemStack> results;
        if (recipe instanceof SmithingTrimRecipe trim) {
            results = trimResults(trim, ingredients, server);
        } else {
            ItemStack output = recipe.getResultItem(server.registryAccess());
            results = output.isEmpty() ? List.of() : List.of(output);
        }
        ItemStack station = recipe.getToastSymbol();
        return new BrowserRecipeEntry(holder.id(), category, layout, columns, rows, ingredients, fuel,
                results, station.isEmpty() ? List.of() : List.of(station),
                duration, experience, false);
    }

    private static List<ItemStack> smithingOptions(java.util.function.Predicate<ItemStack> accepts) {
        List<ItemStack> options = BuiltInRegistries.ITEM.stream().map(ItemStack::new).filter(accepts)
                .limit(BrowserRecipeEntry.MAX_OPTIONS + 1L)
                .sorted(Comparator.comparing(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())).toList();
        if (options.size() > BrowserRecipeEntry.MAX_OPTIONS) throw new IllegalArgumentException("Too many smithing alternatives");
        return options;
    }

    private static List<ItemStack> trimResults(SmithingTrimRecipe recipe, List<List<ItemStack>> ingredients, MinecraftServer server) {
        var registries = server.registryAccess();
        List<ItemStack> templates = ingredients.get(0).stream()
                .filter(stack -> TrimPatterns.getFromTemplate(registries, stack).isPresent()).toList();
        List<ItemStack> materials = ingredients.get(2).stream()
                .filter(stack -> TrimMaterials.getFromIngredient(registries, stack).isPresent()).toList();
        if (templates.isEmpty() || materials.isEmpty()) return List.of();
        List<ItemStack> results = new ArrayList<>();
        int attempts = 0;
        // One real assembly per valid base keeps every output item searchable. These are examples,
        // not the full material product; all allowed templates/materials remain in the input lists.
        nextBase: for (ItemStack base : ingredients.get(1)) {
            for (ItemStack template : templates) for (ItemStack material : materials) {
                if (++attempts > MAX_TRIM_ASSEMBLIES) throw new IllegalArgumentException("Too many trim example combinations");
                SmithingRecipeInput input = new SmithingRecipeInput(template.copy(), base.copy(), material.copy());
                if (!recipe.matches(input, server.overworld())) continue;
                ItemStack result = recipe.assemble(input, registries);
                if (!result.isEmpty()) {
                    results.add(result);
                    continue nextBase;
                }
            }
        }
        return results;
    }

    private record SourceEntry(RecipeHolder<?> recipe, BrowserRecipeEntry presentation) {}
    private record Catalog(long epoch, List<SourceEntry> entries, Set<ResourceLocation> recipeIds, int undisplayed, boolean truncated, long buildNanos) {}
    private record RequestWindow(long tick, int count) {}
}
