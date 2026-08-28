package com.kadamitas.fabricatedbackpacks.client.browser;

import com.kadamitas.fabricatedbackpacks.browser.BrowserCatalogInvalidated;
import com.kadamitas.fabricatedbackpacks.browser.BrowserCatalogPage;
import com.kadamitas.fabricatedbackpacks.browser.BrowserCatalogRequest;
import com.kadamitas.fabricatedbackpacks.browser.BrowserContext;
import com.kadamitas.fabricatedbackpacks.browser.BrowserContextRequest;
import com.kadamitas.fabricatedbackpacks.browser.BrowserTransferRequest;
import com.kadamitas.fabricatedbackpacks.browser.BrowserTransferResult;
import com.kadamitas.fabricatedbackpacks.browser.BrowserWorkstation;
import com.kadamitas.fabricatedbackpacks.network.MenuAction;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Client browsing state; all actual crafting and filter mutations go to the server. */
public final class RecipeBrowserClient {
    private static BrowserClientIndex index = new BrowserClientIndex();
    private static BrowserFluidIndex fluids = new BrowserFluidIndex();
    private static BrowserBookmarks bookmarks;
    private static boolean initialized;
    private static long epoch;
    private static int nextOffset;
    private static int total = -1;
    private static int undisplayed;
    private static boolean truncated;
    private static boolean requestInFlight;
    private static int requestTick;
    private static int ticks;
    private static Object recipeBookCollections;
    private static long serverBuildNanos;
    private static BrowserContext menuContext;
    private static long nextTransferId = 1;

    private RecipeBrowserClient() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ClientPlayNetworking.registerGlobalReceiver(BrowserCatalogPage.TYPE, (page, context) -> receive(page));
        ClientPlayNetworking.registerGlobalReceiver(BrowserCatalogInvalidated.TYPE, (notice, context) -> refresh());
        ClientPlayNetworking.registerGlobalReceiver(BrowserTransferResult.TYPE, (result, context) -> {
            if (Minecraft.getInstance().screen instanceof RecipeBrowserScreen screen) screen.transferResult(result);
        });
        ClientPlayNetworking.registerGlobalReceiver(BrowserContext.TYPE, (result, context) -> {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null && client.player.containerMenu.containerId == result.containerId()
                    && client.screen instanceof RecipeBrowserScreen screen) {
                menuContext = result;
                screen.contextChanged();
            }
        });
        BrowserScreenHooks.initialize();
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> refresh());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> { refresh(); recipeBookCollections = null; });
        ClientTickEvents.END_CLIENT_TICK.register(RecipeBrowserClient::tick);
        ResourceLocation reload = BackpackRegistry.id("recipe_browser_cache");
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public ResourceLocation getFabricId() { return reload; }
            @Override public void onResourceManagerReload(net.minecraft.server.packs.resources.ResourceManager manager) { refresh(); }
        });
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (client) Minecraft.getInstance().execute(RecipeBrowserClient::refresh);
        });
    }

    public static void open(Screen previous) { openForGhost(previous, -1); }

    public static void openItemPicker(Screen previous, BooleanSupplier valid, Consumer<ResourceLocation> selected) {
        openPicker(previous, RegistryPickerScreen.Kind.ITEM, valid, selected);
    }

    public static void openFluidPicker(Screen previous, BooleanSupplier valid, Consumer<ResourceLocation> selected) {
        openPicker(previous, RegistryPickerScreen.Kind.FLUID, valid, selected);
    }

    private static void openPicker(Screen previous, RegistryPickerScreen.Kind kind, BooleanSupplier valid, Consumer<ResourceLocation> selected) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !client.player.isAlive() || !valid.getAsBoolean()) return;
        if (!client.player.containerMenu.getCarried().isEmpty()) {
            client.gui.setOverlayMessage(Component.translatable("browser.fabricated_backpacks.clear_cursor"), false);
            return;
        }
        RegistryPickerScreen screen = new RegistryPickerScreen(previous, kind, valid, selected);
        screen.beginIndex(client);
        client.setScreen(screen);
    }

    public static void openForGhost(Screen previous, int ghostSlot) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null || !client.player.isAlive()) return;
        if (!client.player.containerMenu.getCarried().isEmpty()) {
            client.gui.setOverlayMessage(Component.translatable("browser.fabricated_backpacks.clear_cursor"), false);
            return;
        }
        if (!ClientPlayNetworking.canSend(BrowserCatalogRequest.TYPE)) {
            client.gui.setOverlayMessage(Component.translatable("browser.fabricated_backpacks.unavailable"), false);
            return;
        }
        if (bookmarks == null) bookmarks = new BrowserBookmarks();
        index.begin(client);
        requestContext(client.player.containerMenu.containerId);
        client.setScreen(new RecipeBrowserScreen(previous, client.player.containerMenu.containerId, ghostSlot));
    }

    static BrowserClientIndex index() { return index; }
    static BrowserFluidIndex fluids() { return fluids; }
    static BrowserBookmarks bookmarks() { return bookmarks; }
    static int ticks() { return ticks; }
    static int received() { return nextOffset; }
    static int total() { return total; }
    static int undisplayed() { return undisplayed; }
    static boolean truncated() { return truncated; }
    static long serverBuildNanos() { return serverBuildNanos; }
    static boolean canTransfer(int containerId) {
        return menuContext != null && menuContext.containerId() == containerId && menuContext.workstation() != BrowserWorkstation.NONE;
    }
    static boolean canTransfer(int containerId, ResourceLocation category) {
        return canTransfer(containerId) && menuContext.workstation().accepts(category);
    }
    static boolean limitedCrafting() { return menuContext != null && menuContext.limitedCrafting(); }

    static void refresh() {
        index = new BrowserClientIndex();
        fluids = new BrowserFluidIndex();
        epoch = 0;
        nextOffset = 0;
        total = -1;
        undisplayed = 0;
        truncated = false;
        requestInFlight = false;
        serverBuildNanos = 0;
        menuContext = null;
        Minecraft client = Minecraft.getInstance();
        if (client.player != null && client.screen instanceof RecipeBrowserScreen) {
            index.begin(client);
            requestContext(client.player.containerMenu.containerId);
        }
        if (client.screen instanceof RegistryPickerScreen picker) picker.beginIndex(client);
    }

    static long transfer(ResourceLocation recipe, int containerId, boolean maximum) {
        if (epoch <= 0 || !canTransfer(containerId)) return 0;
        long requestId = nextTransferId++;
        if (nextTransferId <= 0) nextTransferId = 1;
        ClientPlayNetworking.send(new BrowserTransferRequest(epoch, containerId, recipe, requestId, maximum));
        return requestId;
    }

    private static void requestContext(int containerId) {
        menuContext = null;
        if (Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(BrowserContextRequest.TYPE)) {
            ClientPlayNetworking.send(new BrowserContextRequest(containerId));
        }
    }

    static void selectGhost(ResourceLocation item, int containerId, int slot) {
        if (slot < 0 || slot >= 64) return;
        ClientPlayNetworking.send(new MenuAction(containerId, "ghost_registry", slot, 0, item.toString()));
    }

    private static void receive(BrowserCatalogPage page) {
        if (!requestInFlight) return;
        if (page.epoch() != epoch && page.offset() == 0) {
            if (epoch != 0) {
                index = new BrowserClientIndex();
                index.begin(Minecraft.getInstance());
            }
            epoch = page.epoch();
            nextOffset = 0;
        }
        if (page.epoch() != epoch || page.offset() != nextOffset) return;
        requestInFlight = false;
        nextOffset = page.nextOffset();
        total = page.total();
        undisplayed = page.undisplayedRecipes();
        truncated = page.truncated();
        serverBuildNanos = page.buildNanos();
        index.addRecipes(page.entries());
    }

    private static void tick(Minecraft client) {
        ticks++;
        if (client.player == null || client.level == null || client.getConnection() == null) return;
        Object collections = client.player.getRecipeBook().getCollections();
        if (recipeBookCollections != collections) {
            recipeBookCollections = collections;
            if (epoch != 0) refresh();
        }
        index.tick(client);
        fluids.tick(client);
        if (!(client.screen instanceof RecipeBrowserScreen)) return;
        index.begin(client);
        if (requestInFlight && ticks - requestTick > 100) requestInFlight = false;
        if (!requestInFlight && (total < 0 || nextOffset < total) && ClientPlayNetworking.canSend(BrowserCatalogRequest.TYPE)) {
            ClientPlayNetworking.send(new BrowserCatalogRequest(epoch, nextOffset));
            requestInFlight = true;
            requestTick = ticks;
        }
    }
}
