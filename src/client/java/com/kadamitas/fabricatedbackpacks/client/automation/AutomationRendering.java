package com.kadamitas.fabricatedbackpacks.client.automation;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMenus;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMenu;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilterState;
import com.kadamitas.fabricatedbackpacks.automation.engine.SteamEngineMenus;
import com.kadamitas.fabricatedbackpacks.platform.network.ClientPlayNetworking;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.client.resources.model.sprite.MaterialBaker;
import net.minecraft.client.Minecraft;

/** Original native chunk models, moving engine geometry and server-backed menus. */
public final class AutomationRendering {
    private static boolean initialized;
    private static final java.util.Map<ModelBakery, MaterialBaker> PENDING_MATERIALS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private AutomationRendering() {}
    public static void initialize(BusGroup modBus) {
        if (initialized) return;
        initialized = true;
        ClientPlayNetworking.registerGlobalReceiver(ConduitFilterState.TYPE, (state, context) -> {
            var player = Minecraft.getInstance().player;
            if (player != null && player.containerMenu instanceof ConduitMenu menu) menu.applyFilters(state);
        });
        // Forge exposes prepared atlas materials immediately before its model-result event.
        // The live AtlasManager is not uploaded yet and must not be queried on this worker.
        ModelEvent.BakeFluidModels.BUS.addListener((ModelEvent.BakeFluidModels event) ->
                PENDING_MATERIALS.put(event.bakery(), event.materials()));
        ModelEvent.ModifyBakingResult.BUS.addListener((ModelEvent.ModifyBakingResult event) -> {
            MaterialBaker materials = java.util.Objects.requireNonNull(PENDING_MATERIALS.remove(event.getModelBakery()),
                    "Forge model bake did not expose its prepared atlas materials");
            event.getResults().blockStateModels().replaceAll((state, model) ->
                    state.is(AutomationRegistry.CONDUIT_BUNDLE) ? new ConduitBlockModel(model,
                            texture -> materials.get(new Material(texture), () -> "fabricated_backpacks:conduit_bundle").sprite()) : model);
        });
        EntityRenderersEvent.RegisterRenderers.BUS.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerBlockEntityRenderer(AutomationRegistry.STEAM_ENGINE_ENTITY, SteamEngineRenderer::new));
        FMLClientSetupEvent.getBus(modBus).addListener(setup -> setup.enqueueWork(() -> {
            MenuScreens.register(SteamEngineMenus.STEAM_ENGINE, SteamEngineScreen::new);
            MenuScreens.register(SteamEngineMenus.SIDES, SteamEngineSideScreen::new);
            MenuScreens.register(ConduitMenus.CONDUIT, ConduitScreen::new);
        }));
    }
}
