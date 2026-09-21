package com.kadamitas.fabricatedbackpacks.client.automation;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMenus;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMenu;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilterState;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineMenus;
import com.kadamitas.fabricatedbackpacks.platform.network.ClientPlayNetworking;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.minecraft.client.Minecraft;

/** Original native chunk models, moving engine geometry and server-backed menus. */
public final class AutomationRendering {
    private static boolean initialized;
    private AutomationRendering() {}
    public static void initialize(IEventBus modBus) {
        if (initialized) return;
        initialized = true;
        ClientPlayNetworking.registerGlobalReceiver(ConduitFilterState.TYPE, (state, context) -> {
            var player = Minecraft.getInstance().player;
            if (player != null && player.containerMenu instanceof ConduitMenu menu) menu.applyFilters(state);
        });
        modBus.addListener((ModelEvent.ModifyBakingResult event) ->
                event.getBakingResult().blockStateModels().replaceAll((state, model) ->
                        state.is(AutomationRegistry.CONDUIT_BUNDLE) ? new ConduitBlockModel(model, event.getTextureGetter()) : model));
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(AutomationRegistry.STEAM_ENGINE_ENTITY, SteamEngineRenderer::new));
        modBus.addListener((RegisterMenuScreensEvent event) -> {
            event.register(SteamEngineMenus.STEAM_ENGINE, SteamEngineScreen::new);
            event.register(SteamEngineMenus.SIDES, SteamEngineSideScreen::new);
            event.register(ConduitMenus.CONDUIT, ConduitScreen::new);
        });
    }
}
