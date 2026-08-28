package com.kadamitas.fabricatedbackpacks.client.automation;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMenus;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMenu;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilterState;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineMenus;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;

/** Original native chunk models, moving engine geometry and server-backed menus. */
public final class AutomationRendering {
    private static boolean initialized;
    private AutomationRendering() {}
    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ClientPlayNetworking.registerGlobalReceiver(ConduitFilterState.TYPE, (state, context) -> {
            var player = Minecraft.getInstance().player;
            if (player != null && player.containerMenu instanceof ConduitMenu menu) menu.applyFilters(state);
        });
        ModelLoadingPlugin.register(plugin -> plugin.modifyModelAfterBake().register((model, context) -> {
            var id = context.topLevelId();
            return model != null && id != null && id.id().equals(com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry.id("conduit_bundle"))
                    && !id.variant().equals("inventory") ? new ConduitBlockModel(model, context.textureGetter()) : model;
        }));
        BlockEntityRendererRegistry.register(AutomationRegistry.STEAM_ENGINE_ENTITY, SteamEngineRenderer::new);
        MenuScreens.register(SteamEngineMenus.STEAM_ENGINE, SteamEngineScreen::new);
        MenuScreens.register(SteamEngineMenus.SIDES, SteamEngineSideScreen::new);
        MenuScreens.register(ConduitMenus.CONDUIT, ConduitScreen::new);
    }
}
