package com.kadamitas.fabricatedbackpacks.browser;

import com.kadamitas.fabricatedbackpacks.menu.WorkstationMenus;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.FurnaceRecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SmithingRecipeDisplay;
import net.minecraft.world.item.crafting.display.StonecutterRecipeDisplay;
import net.minecraft.world.level.gamerules.GameRules;
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
    private static boolean initialized;

    private RecipeBrowserServer() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        PayloadTypeRegistry.serverboundPlay().register(BrowserCatalogRequest.TYPE, BrowserCatalogRequest.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BrowserTransferRequest.TYPE, BrowserTransferRequest.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(BrowserContextRequest.TYPE, BrowserContextRequest.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BrowserCatalogPage.TYPE, BrowserCatalogPage.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BrowserCatalogInvalidated.TYPE, BrowserCatalogInvalidated.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BrowserTransferResult.TYPE, BrowserTransferResult.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(BrowserContext.TYPE, BrowserContext.STREAM_CODEC);
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
                player.level().getGameRules().get(GameRules.LIMITED_CRAFTING)));
    }

    private static void sendPage(ServerPlayer player, BrowserCatalogRequest request) {
        if (!player.isAlive() || !allowRequest(player) || !ServerPlayNetworking.canSend(player, BrowserCatalogPage.TYPE)) return;
        MinecraftServer server = player.level().getServer();
        Catalog catalog = CATALOGS.computeIfAbsent(server, RecipeBrowserServer::buildCatalog);
        int offset = request.epoch() == catalog.epoch ? request.offset() : 0;
        if (offset > catalog.entries.size()) return;
        int end = Math.min(catalog.entries.size(), offset + BrowserCatalogPage.PAGE_SIZE);
        List<BrowserRecipeEntry> page = new ArrayList<>(end - offset);
        for (int index = offset; index < end; index++) {
            SourceEntry entry = catalog.entries.get(index);
            boolean unlocked = entry.recipe.value().isSpecial() || player.getRecipeBook().contains(entry.recipe.id());
            page.add(new BrowserRecipeEntry(entry.recipe.id().identifier(), entry.category, entry.display, entry.fallbackInputs, unlocked));
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
        Catalog catalog = CATALOGS.get(player.level().getServer());
        if (catalog == null || catalog.epoch != request.epoch() || !catalog.recipeIds.contains(request.recipe())) {
            report(player, request, false, "browser.fabricated_backpacks.stale_recipe");
            if (ServerPlayNetworking.canSend(player, BrowserCatalogInvalidated.TYPE)) ServerPlayNetworking.send(player, BrowserCatalogInvalidated.INSTANCE);
            return;
        }
        ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, request.recipe());
        var current = player.level().getServer().getRecipeManager().byKey(key);
        if (current.isEmpty() || player.level().getGameRules().get(GameRules.LIMITED_CRAFTING)
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
        long tick = player.level().getServer().getTickCount();
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
        Set<Identifier> ids = new HashSet<>();
        int undisplayed = 0;
        boolean truncated = false;
        List<RecipeHolder<?>> recipes = new ArrayList<>(server.getRecipeManager().getRecipes());
        recipes.sort(Comparator.comparing(holder -> holder.id().identifier().toString()));
        for (RecipeHolder<?> holder : recipes) {
            List<RecipeDisplay> displays = holder.value().display();
            if (displays.isEmpty()) { undisplayed++; continue; }
            Identifier category = BuiltInRegistries.RECIPE_TYPE.getKey(holder.value().getType());
            for (RecipeDisplay display : displays) {
                if (!display.isEnabled(server.getWorldData().enabledFeatures())) continue;
                if (entries.size() >= BrowserCatalogPage.MAX_ENTRIES) { truncated = true; break; }
                List<SlotDisplay> fallback = isNativeLayout(display) ? List.of()
                        : holder.value().placementInfo().ingredients().stream().limit(81).map(Ingredient::display).toList();
                BrowserRecipeEntry probe = new BrowserRecipeEntry(holder.id().identifier(), category, display, fallback, false);
                // Bound each encoded entry, so a 64-entry page stays below one
                // MiB even when a datapack attaches unusually large components.
                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
                boolean withinBudget;
                try {
                    probe.write(buffer);
                    withinBudget = buffer.readableBytes() <= 12_000;
                } finally {
                    buffer.release();
                }
                if (!withinBudget) { undisplayed++; continue; }
                entries.add(new SourceEntry(holder, category, display, fallback));
                ids.add(holder.id().identifier());
            }
        }
        long elapsed = System.nanoTime() - started;
        LOGGER.info("Recipe browser indexed {} displays ({} without a bounded display) in {} ms", entries.size(), undisplayed, elapsed / 1_000_000.0);
        return new Catalog(NEXT_EPOCH.getAndIncrement(), List.copyOf(entries), Set.copyOf(ids), undisplayed, truncated, elapsed);
    }

    private static boolean isNativeLayout(RecipeDisplay display) {
        return display instanceof ShapedCraftingRecipeDisplay || display instanceof ShapelessCraftingRecipeDisplay
                || display instanceof FurnaceRecipeDisplay || display instanceof StonecutterRecipeDisplay || display instanceof SmithingRecipeDisplay;
    }

    private record SourceEntry(RecipeHolder<?> recipe, Identifier category, RecipeDisplay display, List<SlotDisplay> fallbackInputs) {}
    private record Catalog(long epoch, List<SourceEntry> entries, Set<Identifier> recipeIds, int undisplayed, boolean truncated, long buildNanos) {}
    private record RequestWindow(long tick, int count) {}
}
