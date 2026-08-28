package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.client.screen.BackpackScreen;
import com.kadamitas.fabricatedbackpacks.client.screen.SlotRulesScreen;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackClientGameTests.*;
import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

final class RulesClientAcceptance {
    private RulesClientAcceptance() {}

    static void run(ClientGameTestContext context, TestSingleplayerContext world) {
        world.getServer().runOnServer(server -> {
            var bag = bag(BackpackTier.GOLD, UpgradeKind.ADVANCED_ALCHEMY, UpgradeKind.ADVANCED_REFILL);
            bag.setFilter(upgrade(bag, 0), 0, PotionContents.createItemStack(Items.POTION, Potions.HEALING));
            bag.setFilter(upgrade(bag, 1), 0, new ItemStack(Items.DIAMOND));
            player(world).getInventory().setItem(6, bag.stack());
            player(world).inventoryMenu.broadcastChanges();
        });
        world.getConnection().waitForClientboundPackets();
        BrowserClientAcceptance.openHovered(context, 6);
        clickButton(context, "1");
        findRules(context);
        context.waitForScreen(SlotRulesScreen.class);
        clickButton(context, "+");
        world.getServer().waitFor(server -> rules(world).settings(upgrade(rules(world), 0)).getIntOr("alchemy_health_0", 75) == 80);
        clickButton(context, "−");
        world.getServer().waitFor(server -> rules(world).settings(upgrade(rules(world), 0)).getIntOr("alchemy_health_0", 75) == 75);
        clickButton(context, "1: hurt");
        world.getServer().waitFor(server -> rules(world).settings(upgrade(rules(world), 0)).getStringOr("alchemy_condition_0", "").equals("NEGATIVE_EFFECT"));
        context.waitFor(client -> client.gui.screen().children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).anyMatch(widget -> widget.getMessage().getString().equals("1: negative effect")));
        check(context.computeOnClient(client -> client.gui.screen().children().stream()
                .filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .filter(widget -> widget.getMessage().getString().equals("+")).noneMatch(widget -> widget.active)),
                "Health threshold buttons are inactive for non-health conditions");
        context.takeScreenshot("alchemy-per-filter-rules");
        clickButton(context, "Back");
        clickButton(context, "2");
        context.waitFor(client -> ((BackpackScreen) client.gui.screen()).getMenu().selectedSlot() == 1);
        findRules(context);
        context.waitForScreen(SlotRulesScreen.class);
        clickButton(context, "1: any");
        world.getServer().waitFor(server -> rules(world).settings(upgrade(rules(world), 1)).getStringOr("refill_target_0", "").equals("MAIN_HAND"));
        context.takeScreenshot("refill-per-filter-targets");
        clickButton(context, "Back");
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        context.waitFor(client -> client.gui.screen() == null);
        contextualTransfer(context, world);
    }

    private static void findRules(ClientGameTestContext context) {
        for (int page = 0; page < 10; page++) {
            var labels = context.computeOnClient(client -> client.gui.screen().children().stream()
                    .filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                    .filter(widget -> widget.visible && widget.active).map(widget -> widget.getMessage().getString()).toList());
            if (labels.contains("Slot rules")) { clickButton(context, "Slot rules"); return; }
            clickButton(context, labels.stream().filter(label -> label.startsWith("More ")).findFirst().orElseThrow());
        }
        throw new AssertionError("No slot rules page");
    }

    private static void contextualTransfer(ClientGameTestContext context, TestSingleplayerContext world) {
        var originalEquipment = world.getServer().computeOnServer(server -> com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.get(player(world)).copy());
        BlockPos chestPos = new BlockPos(0, 80, 2);
        try {
            world.getServer().runOnServer(server -> {
                var bag = bag(BackpackTier.GOLD, UpgradeKind.DEPOSIT);
                bag.setItem(0, new ItemStack(Items.AMETHYST_SHARD, 23));
                com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.set(player(world), bag.stack());
                server.overworld().setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
            });
            world.getConnection().waitForClientboundPackets();
            context.getInput().lookAt(chestPos);
            context.waitTicks(3);
            context.getInput().pressKey(GLFW.GLFW_KEY_C);
            world.getServer().waitFor(server -> count((Container) server.overworld().getBlockEntity(chestPos), Items.AMETHYST_SHARD) == 23);
            check(world.getServer().computeOnServer(server -> BagInventory.of(com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.get(player(world))).isEmpty()),
                    "A real C keypress deposits the equipped bag's items exactly once");
            context.takeScreenshot("contextual-C-transfer");
        } finally {
            world.getServer().runOnServer(server -> com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment.set(player(world), originalEquipment));
            world.getConnection().waitForClientboundPackets();
        }
    }

    private static BagInventory rules(TestSingleplayerContext world) { return BagInventory.of(player(world).getInventory().getItem(6)); }
}
