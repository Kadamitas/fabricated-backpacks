package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class PiglinSafetyGameTests {
    private PiglinSafetyGameTests() {}

    public static void goldBackpackRules(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        ItemStack gold = new ItemStack(BackpackRegistry.item(BackpackTier.GOLD));
        player.setItemInHand(InteractionHand.MAIN_HAND, gold.copy());
        player.getInventory().setItem(5, gold.copy());
        helper.assertFalse(PiglinAi.isWearingSafeArmor(player), "Holding or carrying a gold backpack does not count as wearing it");
        for (BackpackTier tier : BackpackTier.values()) {
            BackpackEquipment.set(player, new ItemStack(BackpackRegistry.item(tier)));
            helper.assertValueEqual(PiglinAi.isWearingSafeArmor(player), tier == BackpackTier.GOLD,
                    "Vanilla piglin equipment predicate distinguishes the native " + tier + " backpack");
        }
        BackpackEquipment.set(player, new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER)));
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.GOLDEN_HELMET));
        helper.assertTrue(PiglinAi.isWearingSafeArmor(player), "Ordinary golden armor remains valid alongside a non-gold native backpack");
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        BackpackEquipment.set(player, gold);
        Piglin piglin = helper.spawn(EntityTypes.PIGLIN, new BlockPos(3, 1, 3));
        piglin.getBrain().setActiveActivityIfPossible(Activity.IDLE);
        PiglinAi.angerNearbyPiglins(helper.getLevel(), player, false);
        helper.assertTrue(piglin.getBrain().getMemory(MemoryModuleType.ANGRY_AT).filter(player.getUUID()::equals).isPresent(),
                "Wearing a gold backpack does not suppress vanilla provocation anger");
        piglin.discard();
        BackpackEquipment.set(player, ItemStack.EMPTY);
        helper.assertFalse(PiglinAi.isWearingSafeArmor(player), "Removing native gold equipment removes only its passive protection");
        helper.succeed();
    }
}
