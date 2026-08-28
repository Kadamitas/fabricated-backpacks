package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
import com.kadamitas.fabricatedbackpacks.menu.EquipmentMenu;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.gamerules.GameRules;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

final class EquipmentGameTests {
    private EquipmentGameTests() {}

    static void nativeSlotAndDeath(GameTestHelper helper) {
        var level = helper.getLevel();
        var server = level.getServer();
        boolean keep = level.getGameRules().get(GameRules.KEEP_INVENTORY);
        try {
            level.getGameRules().set(GameRules.KEEP_INVENTORY, false, server);
            var player = player(helper);
            var foreign = player(helper);
            BagInventory bag = bag(BackpackTier.DIAMOND);
            bag.setItem(0, new ItemStack(Items.DIAMOND, 41));
            bag.dye(0x113355, 0xccaaff);
            ItemStack expected = bag.stack().copy();
            String identity = bag.identity();
            ItemStack armor = new ItemStack(Items.DIAMOND_CHESTPLATE);
            armor.setDamageValue(9);
            player.setItemSlot(EquipmentSlot.CHEST, armor);
            player.getInventory().setItem(9, bag.stack());
            BackpackMenus.openEquipment(player);
            helper.assertTrue(player.containerMenu instanceof EquipmentMenu, "Native equipment UI opens through its registered server menu");
            var menu = (EquipmentMenu) player.containerMenu;
            int source = menuSlot(menu, player.getInventory(), 9);
            helper.assertFalse(menu.getSlot(0).mayPlace(new ItemStack(Items.DIAMOND)), "Dedicated slot refuses arbitrary items");
            helper.assertTrue(menu.quickMoveStack(foreign, source).isEmpty(), "Another player cannot mutate an equipment session");
            helper.assertTrue(BackpackEquipment.get(player).isEmpty(), "Rejected foreign transfer leaves the native slot empty");
            menu.clicked(source, 0, ContainerInput.QUICK_MOVE, player);
            assertStack(helper, BackpackEquipment.get(player), expected, "Actual equipment shift-click stores exactly the original bag");
            helper.assertTrue(BackpackEquipment.visual(player).is(expected.getItem()), "Equipping publishes the matching public tier");
            helper.assertFalse(BackpackEquipment.visual(player).has(BagComponents.CONTENTS) || BackpackEquipment.visual(player).has(BagComponents.IDENTITY),
                    "The public wearable attachment contains neither undisplayed contents nor private identity");
            helper.assertValueEqual(BackpackEquipment.visual(player).get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA),
                    expected.get(net.minecraft.core.component.DataComponents.CUSTOM_MODEL_DATA), "Private storage filtering retains the actual wearable colors");
            helper.assertTrue(player.getInventory().getItem(9).isEmpty(), "Equipping removes the inventory source");
            assertStack(helper, player.getItemBySlot(EquipmentSlot.CHEST), armor, "The backpack slot does not replace chest armor");
            player.closeContainer();
            player.hurtServer(level, player.damageSources().genericKill(), Float.MAX_VALUE);
            helper.assertFalse(player.isAlive(), "Loaded survival player actually enters the vanilla death pipeline");
            helper.assertTrue(BackpackEquipment.get(player).isEmpty(), "Normal death clears the native slot before respawn copying");
            helper.assertTrue(BackpackEquipment.visual(player).isEmpty(), "Normal death also clears the visible wearable");
            var dropped = level.getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(3), entity -> identity.equals(entity.getItem().getOrDefault(BagComponents.IDENTITY, "")));
            helper.assertValueEqual(dropped.size(), 1, "Normal death drops exactly one equipped backpack");
            assertStack(helper, dropped.getFirst().getItem(), expected, "Death drop preserves contents and dyes");
            var respawned = server.getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
            helper.assertTrue(BackpackEquipment.get(respawned).isEmpty(), "Respawn does not duplicate the dropped attachment");
            dropped.getFirst().discard();

            level.getGameRules().set(GameRules.KEEP_INVENTORY, true, server);
            var keeper = player(helper);
            BagInventory kept = bag(BackpackTier.NETHERITE);
            kept.setItem(119, new ItemStack(Items.EMERALD, 29));
            BackpackEquipment.set(keeper, kept.stack());
            ItemStack saved = BackpackEquipment.get(keeper).copy();
            keeper.hurtServer(level, keeper.damageSources().genericKill(), Float.MAX_VALUE);
            helper.assertFalse(keeper.isAlive(), "Keep-inventory scenario actually executes a player death");
            helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, keeper.getBoundingBox().inflate(3), entity -> kept.identity().equals(entity.getItem().getOrDefault(BagComponents.IDENTITY, ""))).isEmpty(), "Keep-inventory death does not drop the native bag");
            var keptRespawn = server.getPlayerList().respawn(keeper, false, Entity.RemovalReason.KILLED);
            assertStack(helper, BackpackEquipment.get(keptRespawn), saved, "Keep-inventory respawn copies the exact native backpack state");
            helper.assertTrue(BackpackEquipment.visual(keptRespawn).is(saved.getItem()), "Keep-inventory respawn rebuilds the public wearable appearance");

            level.getGameRules().set(GameRules.KEEP_INVENTORY, false, server);
            var cursedPlayer = player(helper);
            BagInventory cursed = bag(BackpackTier.LEATHER);
            cursed.setItem(0, new ItemStack(Items.DIAMOND, 5));
            cursed.stack().enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.VANISHING_CURSE), 1);
            BackpackEquipment.set(cursedPlayer, cursed.stack());
            cursedPlayer.hurtServer(level, cursedPlayer.damageSources().genericKill(), Float.MAX_VALUE);
            helper.assertFalse(cursedPlayer.isAlive(), "Vanishing scenario actually executes a player death");
            helper.assertTrue(BackpackEquipment.get(cursedPlayer).isEmpty(), "Curse of Vanishing clears the native slot");
            helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, cursedPlayer.getBoundingBox().inflate(3), entity -> cursed.identity().equals(entity.getItem().getOrDefault(BagComponents.IDENTITY, ""))).isEmpty(), "Vanishing consumes the cursed bag instead of dropping another owned copy");
            var cursedRespawn = server.getPlayerList().respawn(cursedPlayer, false, Entity.RemovalReason.KILLED);
            helper.assertTrue(BackpackEquipment.get(cursedRespawn).isEmpty(), "Vanished attachment is not restored on respawn");
        } finally {
            level.getGameRules().set(GameRules.KEEP_INVENTORY, keep, server);
        }
        helper.succeed();
    }
}
