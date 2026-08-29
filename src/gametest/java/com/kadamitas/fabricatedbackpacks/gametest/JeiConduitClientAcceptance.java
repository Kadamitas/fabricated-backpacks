package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilterMode;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.client.automation.ConduitScreen;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.fabric.ingredients.fluids.IJeiFluidIngredient;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.material.Fluids;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.*;

/** Optional test plugin observes JEI's public API; acceptance actions use native keys and mouse drags. */
public final class JeiConduitClientAcceptance implements IModPlugin {
    private static volatile IJeiRuntime runtime;
    @Override public Identifier getPluginUid() { return Identifier.fromNamespaceAndPath("fabricated_backpacks_tests", "jei_acceptance"); }
    @Override public void onRuntimeAvailable(IJeiRuntime available) { runtime = available; }
    @Override public void onRuntimeUnavailable() { runtime = null; }

    static void run(ClientGameTestContext context) {
        Path receipt = ClientAcceptanceFiles.ROOT.resolve("automation-jei-pass.json");
        try {
            Files.deleteIfExists(receipt);
            var jei = FabricLoader.getInstance().getModContainer("jei").orElseThrow(() ->
                    new AssertionError("JEI acceptance requires the actual optional runtime: use -PwithJei=true"));
            try (var world = context.worldBuilder().create()) {
                world.getServer().runCommand("time set day");
                world.getServer().runCommand("weather clear");
                world.getServer().runOnServer(server -> player(world).setGameMode(GameType.SURVIVAL));
                ConduitFilterClientAcceptance.prepare(context, world);
                var inventory = world.getServer().computeOnServer(server -> ConduitFilterClientAcceptance.inventory(world));
                ConduitFilterClientAcceptance.open(context, world);
                context.waitFor(client -> runtime != null && runtime.getIngredientListOverlay().isListDisplayed());
                ConduitFilterClientAcceptance.selectPanel(context, ConduitKind.ITEM);
                ConduitFilterClientAcceptance.setMode(context, ConduitKind.ITEM, ConduitFilterMode.ALLOW);
                assertExclusion(context);
                search(context, "@minecraft cobblestone -mossy -infested");
                drag(context, ingredient -> ingredient.getIngredient() instanceof ItemStack stack && stack.is(Items.COBBLESTONE), 0);
                await(context, ConduitKind.ITEM, 0, "cobblestone");
                context.takeScreenshot("automation-jei-item-search-and-ghost");
                ConduitFilterClientAcceptance.selectPanel(context, ConduitKind.FLUID);
                ConduitFilterClientAcceptance.setMode(context, ConduitKind.FLUID, ConduitFilterMode.BLOCK);
                search(context, "water");
                drag(context, ingredient -> ingredient.getIngredient() instanceof IJeiFluidIngredient fluid
                        && fluid.getFluidVariant().getFluid() == Fluids.WATER, 0);
                await(context, ConduitKind.FLUID, 0, "water");
                context.takeScreenshot("automation-jei-fluid-search-and-ghost");
                search(context, "@minecraft lava bucket");
                drag(context, ingredient -> ingredient.getIngredient() instanceof ItemStack stack && stack.is(Items.LAVA_BUCKET), 1);
                await(context, ConduitKind.FLUID, 1, "lava");
                context.takeScreenshot("automation-jei-bucket-as-fluid-ghost");
                world.getServer().runOnServer(server -> {
                    var pipe = ConduitFilterClientAcceptance.pipe(server.overworld());
                    check(pipe.filter(ConduitKind.ITEM, Direction.NORTH).entry(0).orElseThrow().equals(BuiltInRegistries.ITEM.getKey(Items.COBBLESTONE)),
                            "Actual JEI item drag reaches the server-authoritative physical face filter");
                    check(pipe.filter(ConduitKind.FLUID, Direction.NORTH).entry(0).orElseThrow().equals(BuiltInRegistries.FLUID.getKey(Fluids.WATER))
                                    && pipe.filter(ConduitKind.FLUID, Direction.NORTH).entry(1).orElseThrow().equals(BuiltInRegistries.FLUID.getKey(Fluids.LAVA)),
                            "Actual JEI fluid and filled-bucket drags both reach canonical fluid filters");
                    check(IntStream.range(0, inventory.size()).allMatch(slot -> ItemStack.matches(inventory.get(slot), player(world).getInventory().getItem(slot)))
                                    && player(world).containerMenu.getCarried().isEmpty(),
                            "Survival JEI ghost dragging creates no item, consumes no bucket and changes no inventory stack");
                });
                ConduitFilterClientAcceptance.close(context);
            }
            ClientAcceptanceFiles.copyTree(Path.of("screenshots"), ClientAcceptanceFiles.ROOT.resolve("automation-jei-screenshots"));
            var proof = new com.google.gson.JsonObject();
            proof.addProperty("scope", "automation_jei"); proof.addProperty("passed", true);
            proof.addProperty("pid", ProcessHandle.current().pid()); proof.addProperty("recorded_at", System.currentTimeMillis());
            proof.addProperty("jei_version", jei.getMetadata().getVersion().getFriendlyString());
            proof.add("checks", new com.google.gson.Gson().toJsonTree(List.of(
                    "Real optional JEI runtime, native search-field click/text input and mouse drag of cobblestone into an allow ghost.",
                    "Native JEI fluid and lava-bucket drags reach water/lava block ghosts without consuming or granting inventory.",
                    "Entire attached filter panel is excluded from JEI overlay placement; server receives only canonical resource IDs.")));
            Files.writeString(receipt, proof.toString());
            System.out.println("FABRICATED_BACKPACKS_JEI_ACCEPTANCE_PASS " + receipt);
        } catch (Exception failure) { throw new AssertionError("Optional JEI conduit acceptance failed", failure); }
    }

    private static void assertExclusion(ClientGameTestContext context) {
        check(context.computeOnClient(client -> {
            var screen = (ConduitScreen) client.gui.screen();
            var panel = screen.filterPanelBounds().orElseThrow();
            return runtime.getScreenHelper().getGuiExclusionAreas(screen).anyMatch(area ->
                    area.getX() <= panel.left() && area.getY() <= panel.top()
                            && area.getX() + area.getWidth() >= panel.right() && area.getY() + area.getHeight() >= panel.bottom());
        }), "JEI observes an exclusion area covering the complete attached filter panel");
    }
    private static void search(ClientGameTestContext context, String query) {
        // This Fabric JEI build leaves focusSearch unbound. Use its visible bottom-right field.
        int[] point = context.computeOnClient(client -> {
            var properties = runtime.getScreenHelper().getGuiProperties(client.gui.screen()).orElseThrow();
            return new int[]{(properties.guiRight() + properties.screenWidth()) / 2, properties.screenHeight() - 12};
        });
        clickAt(context, point[0], point[1], GLFW.GLFW_MOUSE_BUTTON_LEFT);
        try { context.waitFor(client -> runtime.getIngredientListOverlay().hasKeyboardFocus()); }
        catch (AssertionError failure) {
            context.takeScreenshot("automation-jei-search-focus-failure");
            throw new AssertionError("Native click must focus the visible JEI search field", failure);
        }
        context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_CONTROL);
        try { context.getInput().pressKey(GLFW.GLFW_KEY_A); }
        finally { context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_CONTROL); }
        context.getInput().pressKey(GLFW.GLFW_KEY_BACKSPACE);
        context.getInput().typeChars(query);
        context.waitFor(client -> runtime.getIngredientFilter().getFilterText().equals(query));
        context.waitTicks(4);
    }
    private static void drag(ClientGameTestContext context, Predicate<ITypedIngredient<?>> matches, int ghost) {
        int[] screen = context.computeOnClient(client -> {
            var properties = runtime.getScreenHelper().getGuiProperties(client.gui.screen()).orElseThrow();
            return new int[]{properties.guiRight() + 4, properties.screenWidth(), properties.screenHeight()};
        });
        boolean found = false;
        outer: for (int y = 8; y < screen[2] - 36; y += 9) for (int x = screen[0]; x < screen[1] - 8; x += 9) {
            cursor(context, x, y);
            if (context.computeOnClient(client -> runtime.getIngredientListOverlay().getIngredientUnderMouse().filter(matches).isPresent())) {
                found = true; break outer;
            }
        }
        check(found, "The searched JEI ingredient is visible and can be targeted with the native cursor");
        var target = context.computeOnClient(client -> ((ConduitScreen) client.gui.screen()).filterTargets().get(ghost).bounds());
        context.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        try {
            context.waitTicks(2);
            cursor(context, target.left() + 8, target.top() + 8);
            context.waitTicks(2);
        } finally { context.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT); }
        context.waitTicks(3);
    }
    private static void cursor(ClientGameTestContext context, int x, int y) {
        double[] point = context.computeOnClient(client -> new double[]{x * client.getWindow().getScreenWidth() / (double) client.getWindow().getGuiScaledWidth(),
                y * client.getWindow().getScreenHeight() / (double) client.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(point[0], point[1]);
    }
    private static void await(ClientGameTestContext context, ConduitKind kind, int slot, String id) {
        context.waitFor(client -> client.gui.screen() instanceof ConduitScreen screen
                && screen.getMenu().filter(kind).entry(slot).filter(Identifier.withDefaultNamespace(id)::equals).isPresent());
    }
}
