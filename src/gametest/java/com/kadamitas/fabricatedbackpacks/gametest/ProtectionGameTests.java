package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

final class ProtectionGameTests {
    private ProtectionGameTests() {}

    static void everlastingItemLifecycle(GameTestHelper helper) {
        var level = helper.getLevel();
        var origin = helper.absoluteVec(new Vec3(3.5, 2, 3.5));
        var bag = bag(BackpackTier.LEATHER, UpgradeKind.EVERLASTING);
        bag.setItem(0, new ItemStack(Items.DIAMOND, 17));
        var protectedItem = new ItemEntity(level, origin.x, origin.y, origin.z, bag.stack().copy());
        var ordinary = new ItemEntity(level, origin.x + 2, origin.y, origin.z, bag(BackpackTier.LEATHER).stack());
        level.addFreshEntity(protectedItem);
        level.addFreshEntity(ordinary);
        protectedItem.makeFakeItem();
        ordinary.makeFakeItem();
        protectedItem.tick();
        ordinary.tick();
        helper.assertFalse(protectedItem.isRemoved(), "Everlasting resets actual ItemEntity age before the despawn threshold");
        helper.assertTrue(ordinary.isRemoved(), "The identical natural age threshold still despawns an ordinary backpack");
        for (var damage : java.util.List.of(level.damageSources().inFire(), level.damageSources().lava(), level.damageSources().generic(), level.damageSources().explosion(null, null))) {
            helper.assertFalse(protectedItem.hurtServer(level, damage, 1000), "Real ItemEntity damage is rejected for " + damage);
            helper.assertFalse(protectedItem.isRemoved(), "Protection cannot be bypassed by a damage source");
        }
        protectedItem.setPos(origin.x, level.getMinY() - 40, origin.z);
        protectedItem.setDeltaMovement(0, -10, 0);
        protectedItem.tick();
        helper.assertTrue(protectedItem.getY() > level.getMinY() && !protectedItem.isRemoved(), "Void recovery moves the real dropped item to a safe height before removal");
        helper.assertValueEqual(count(com.kadamitas.fabricatedbackpacks.storage.BagInventory.of(protectedItem.getItem()), Items.DIAMOND), 17, "Despawn, damage and void handling preserve the exact filled backpack");
        protectedItem.discard();
        helper.succeed();
    }

    static void everlastingLavaAndExplosion(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos lava = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
        var item = new ItemEntity(level, lava.getX() + .5, lava.getY() + .1, lava.getZ() + .5, bag(BackpackTier.LEATHER, UpgradeKind.EVERLASTING).stack());
        item.setNeverPickUp();
        level.addFreshEntity(item);
        item.tick();
        item.tick();
        item.tick();
        helper.assertTrue(item.isInLava() && item.getDeltaMovement().y > 0 && !item.isRemoved(), "The actual dropped bag rises while immersed in lava");
        item.discard();
        BlockPos protectedPos = helper.absolutePos(new BlockPos(5, 1, 5));
        BlockPos ordinaryPos = protectedPos.east();
        level.setBlockAndUpdate(protectedPos, BackpackRegistry.block(BackpackTier.LEATHER).defaultBlockState());
        level.setBlockAndUpdate(ordinaryPos, BackpackRegistry.block(BackpackTier.LEATHER).defaultBlockState());
        var protectedBag = (BackpackBlockEntity) level.getBlockEntity(protectedPos);
        protectedBag.setStack(bag(BackpackTier.LEATHER, UpgradeKind.EVERLASTING).stack());
        protectedBag.setItem(0, new ItemStack(Items.EMERALD, 9));
        level.explode(null, protectedPos.getX() + 1, protectedPos.getY() + 1, protectedPos.getZ() + .5, 4, Level.ExplosionInteraction.TNT);
        helper.assertTrue(level.getBlockEntity(protectedPos) == protectedBag, "A real explosion preserves the placed everlasting bag");
        helper.assertFalse(level.getBlockState(ordinaryPos).is(BackpackRegistry.block(BackpackTier.LEATHER)), "The same explosion destroys the nearby ordinary backpack");
        helper.assertValueEqual(count(protectedBag, Items.EMERALD), 9, "Explosion resistance retains stored contents");
        helper.succeed();
    }
}
