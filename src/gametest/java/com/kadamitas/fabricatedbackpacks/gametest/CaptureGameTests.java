package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.gameplay.MobCapture;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

final class CaptureGameTests {
    private CaptureGameTests() {}

    static void capturePersistenceAndSafeRelease(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = player(helper);
        player.setPos(helper.absoluteVec(new Vec3(3.5, 1, 5.5)));
        var pig = helper.spawn(EntityTypes.PIG, new BlockPos(3, 1, 3));
        pig.setNoAi(true);
        pig.setPersistenceRequired();
        pig.setCustomName(Component.literal("Traveling pig"));
        pig.setCustomNameVisible(true);
        pig.setHealth(7);
        pig.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 600, 1));
        var id = pig.getUUID();
        BagInventory captured = bag(BackpackTier.GOLD, UpgradeKind.MOB_CATCHER);
        captured.setItem(80, new ItemStack(Items.LAPIS_LAZULI, 32));
        helper.assertTrue(MobCapture.capture(captured, pig, player), "Basic catcher captures an eligible nearby passive mob");
        helper.assertFalse(pig.isAlive(), "Capture removes the original world entity exactly once");
        helper.assertFalse(MobCapture.capture(captured, pig, player), "Repeated capture cannot duplicate a removed entity");
        int[] cells = captured.settings().getIntArray("captured_slots").orElseThrow();
        helper.assertTrue(cells.length >= 9, "Mob capture reserves real storage cells according to health and size");
        for (int cell : cells) helper.assertFalse(captured.canPlaceItem(cell, new ItemStack(Items.DIAMOND)), "Each captured cell refuses ordinary item insertion");
        helper.assertFalse(captured.canRemoveUpgrade(0), "Capture upgrade cannot be removed while it owns a stored entity");
        BagInventory restored = BagInventory.of(roundTrip(level, captured.stack()));
        helper.assertValueEqual(restored.settings().getListOrEmpty("captured_entities").size(), 1, "Captured entity data survives the actual bag codec");

        BagInventory full = bag(BackpackTier.LEATHER, UpgradeKind.MOB_CATCHER);
        for (int slot = 0; slot < full.getContainerSize(); slot++) full.setItem(slot, new ItemStack(Items.DIRT, 64));
        var second = helper.spawn(EntityTypes.PIG, new BlockPos(4, 1, 4));
        second.setNoAi(true);
        ItemStack before = full.stack().copy();
        helper.assertFalse(MobCapture.capture(full, second, player), "A full bag cannot capture a mob without a real rectangle");
        helper.assertTrue(second.isAlive(), "Failed capture leaves the world entity alive");
        assertStack(helper, full.stack(), before, "Failed capture leaves all stored items unchanged");

        player.setPos(helper.absoluteVec(new Vec3(5.5, 1, 5.5)));
        var zombie = helper.spawn(EntityTypes.ZOMBIE, new BlockPos(6, 1, 4));
        zombie.setNoAi(true);
        var zombieId = zombie.getUUID();
        helper.assertFalse(MobCapture.capture(bag(BackpackTier.NETHERITE, UpgradeKind.MOB_CATCHER), zombie, player), "Basic catcher rejects a healthy hostile mob above its cost limit");
        BagInventory advanced = bag(BackpackTier.NETHERITE, UpgradeKind.ADVANCED_MOB_CATCHER);
        helper.assertTrue(MobCapture.capture(advanced, zombie, player), "Advanced catcher accepts a hostile mob with sufficient real storage space");

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(level.getEntity(id) == null, "Captured entity no longer occupies the world UUID registry");
            BlockPos blocked = helper.absolutePos(new BlockPos(2, 1, 2));
            level.setBlockAndUpdate(blocked, Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(blocked.above(), Blocks.STONE.defaultBlockState());
            ItemStack stored = restored.stack().copy();
            helper.assertFalse(MobCapture.release(restored, 0, player, Vec3.atBottomCenterOf(blocked)), "Release refuses an occupied world volume");
            assertStack(helper, restored.stack(), stored, "Failed release preserves all entity data and reserved cells");
            helper.assertTrue(MobCapture.release(restored, 0, player, helper.absoluteVec(new Vec3(4.5, 1, 2.5))), "Safe release restores the captured entity into an empty volume");
            var released = level.getEntity(id);
            helper.assertTrue(released instanceof Mob, "Release preserves the original UUID and entity type");
            helper.assertValueEqual(released.getName().getString(), "Traveling pig", "Release preserves custom names");
            helper.assertValueEqual(((Mob) released).getHealth(), 7F, "Release preserves current health");
            helper.assertTrue(((Mob) released).isNoAi() && ((Mob) released).hasEffect(MobEffects.FIRE_RESISTANCE), "Release preserves mob flags and active effects");
            helper.assertValueEqual(restored.settings().getIntArray("captured_slots").orElseThrow().length, 0, "Successful release frees precisely its owned cells");
            helper.assertTrue(restored.canRemoveUpgrade(0), "An empty catcher becomes removable again");
            helper.assertValueEqual(count(restored, Items.LAPIS_LAZULI), 32, "Capture and release never consume unrelated bag contents");
            helper.assertFalse(MobCapture.release(restored, 0, player, helper.absoluteVec(new Vec3(1.5, 1, 1.5))), "Repeated release cannot spawn another copy");
            helper.assertTrue(MobCapture.release(advanced, 0, player, helper.absoluteVec(new Vec3(6.5, 1, 2.5))), "Advanced hostile capture also releases safely");
            helper.assertTrue(level.getEntity(zombieId) != null, "Advanced release retains its original world UUID");
            released.discard();
            level.getEntity(zombieId).discard();
            second.discard();
            helper.succeed();
        });
    }
}
