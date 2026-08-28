package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.network.WorkstationState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Adds portable-only controls while leaving vanilla result slots and recipe rules intact. */
public final class WorkstationControls {
    private static final Map<Screen, List<Control>> CONTROLS = new WeakHashMap<>();
    private static int containerId = -1;
    private static CompoundTag state = new CompoundTag();
    private record Control(int action, Button button) {}
    private WorkstationControls() {}

    public static void initialize() {
        ClientPlayNetworking.registerGlobalReceiver(WorkstationState.TYPE, (packet, context) -> context.client().execute(() -> {
            if (context.player().containerMenu.containerId != packet.containerId()) return;
            containerId = packet.containerId();
            state = packet.settings().copyTag();
            update(context.client());
        }));
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof AbstractContainerScreen<?> container)) return;
            var menu = container.getMenu();
            if (!(menu instanceof CraftingMenu || menu instanceof AnvilMenu || menu instanceof SmithingMenu || menu instanceof StonecutterMenu)) return;
            List<Control> controls = new ArrayList<>();
            add(controls, screen, 10000, "Results: backpack", 8, 6, 112);
            add(controls, screen, 10001, "Refill: off", 124, 6, 84);
            add(controls, screen, 10002, "Recipe <", 8, 22, 64);
            add(controls, screen, 10003, "Recipe >", 76, 22, 64);
            add(controls, screen, -1, "Choose recipe", 144, 22, 88);
            for (int index = 0; index < 4; index++) add(controls, screen, 10010 + index, "Recent " + (index + 1), 8 + 56 * index, 22, 52);
            add(controls, screen, -2, "All recipes", 232, 22, 80);
            CONTROLS.put(screen, controls);
            update(client);
        });
        ClientTickEvents.END_CLIENT_TICK.register(WorkstationControls::update);
    }
    private static void add(List<Control> controls, Screen screen, int action, String label, int x, int y, int width) {
        Button button = Button.builder(Component.literal(label), ignored -> {
            Minecraft client = Minecraft.getInstance();
            if (client.gameMode != null && client.player != null && client.player.containerMenu.containerId == containerId) {
                if (action < 0) client.gui.setScreen(new WorkstationChoiceScreen(screen, containerId));
                else client.gameMode.handleInventoryButtonClick(containerId, action);
            }
        }).bounds(x, y, width, 14).build();
        button.visible = false;
        controls.add(new Control(action, button));
        Screens.getWidgets(screen).add(button);
    }
    private static void update(Minecraft client) {
        Screen screen = client.gui.screen();
        List<Control> controls = CONTROLS.get(screen);
        if (controls == null) return;
        boolean valid = client.player != null && client.player.containerMenu.containerId == containerId
                && screen instanceof AbstractContainerScreen<?> container && container.getMenu().containerId == containerId;
        String family = state.getStringOr("family", "");
        String[] recent = state.getStringOr("recent_recipes", "").split(",");
        String[] choices = state.getStringOr("choices", "").split(",");
        for (Control control : controls) {
            int action = control.action();
            Button button = control.button();
            button.visible = valid && switch (action) {
                case 10000 -> true;
                case 10001 -> family.equals("crafting") || family.equals("stonecutter");
                case 10002, 10003 -> family.equals("crafting") && choices.length > 1;
                case -1 -> family.equals("crafting") && choices.length > 1;
                case -2 -> family.equals("stonecutter") && choices.length >= 13;
                default -> family.equals("stonecutter") && action >= 10010 && action - 10010 < recent.length && !recent[action - 10010].isBlank();
            };
            if (action == 10000) button.setMessage(Component.literal("Results: " + (state.getStringOr("result_destination", "STORAGE").equalsIgnoreCase("PLAYER") ? "player" : "backpack")));
            if (action == 10001) button.setMessage(Component.literal("Refill: " + (state.getBooleanOr("grid_refill", false) ? "on" : "off")));
            if (action >= 10010 && button.visible) button.setTooltip(Tooltip.create(Component.literal(recent[action - 10010])));
            else if (action == 10000) button.setTooltip(Tooltip.create(Component.literal("Destination for shift-clicked results. A full destination never consumes ingredients.")));
        }
    }
    static CompoundTag currentState(int id) { return id == containerId ? state.copy() : new CompoundTag(); }
}
