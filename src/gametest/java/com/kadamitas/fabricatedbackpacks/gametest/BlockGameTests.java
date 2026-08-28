package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlock;
import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

final class BlockGameTests {
    private BlockGameTests() {}

    static void creativeCopiesHaveIndependentIdentities(GameTestHelper helper) {
        var player = player(helper);
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        player.setShiftKeyDown(true);
        var original = bag(BackpackTier.GOLD, UpgradeKind.INCEPTION);
        var child = bag(BackpackTier.LEATHER);
        child.setItem(0, new ItemStack(Items.DIAMOND, 17));
        original.setItem(0, child.stack());
        String rootId = original.identity(), childId = child.identity();
        player.getInventory().setItem(0, original.stack());
        var position = helper.absolutePos(new BlockPos(3, 1, 3));
        var hit = new BlockHitResult(Vec3.atCenterOf(position.below()).add(0, .5, 0), Direction.UP, position.below(), false);
        player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
        var placed = (BackpackBlockEntity) helper.getLevel().getBlockEntity(position);
        helper.assertTrue(placed != null, "Creative item interaction actually places a backpack");
        helper.assertValueEqual(player.getMainHandItem().getCount(), 1, "Creative placement retains the source backpack");
        helper.assertFalse(placed.inventory().identity().equals(rootId), "Placed creative copy has an independent outer identity");
        var copiedChild = BagInventory.of(placed.inventory().getItem(0));
        helper.assertFalse(copiedChild.identity().equals(childId), "Creative copying also forks the nested backpack identity");
        helper.assertValueEqual(count(copiedChild, Items.DIAMOND), 17, "Creative copying retains nested contents");
        copiedChild.setItem(0, new ItemStack(Items.DIAMOND, 2));
        helper.assertValueEqual(count(BagInventory.of(original.getItem(0)), Items.DIAMOND), 17, "Editing the creative copy does not alias the original nested contents");
        helper.succeed();
    }

    static void displayPacketsExcludePrivateStorage(GameTestHelper helper) {
        var pos = helper.absolutePos(new BlockPos(3, 1, 3));
        helper.getLevel().setBlockAndUpdate(pos, BackpackRegistry.block(BackpackTier.GOLD).defaultBlockState());
        var block = (BackpackBlockEntity) helper.getLevel().getBlockEntity(pos);
        var bag = bag(BackpackTier.GOLD, UpgradeKind.STACK_UPGRADE_TIER_4);
        bag.setItem(0, new ItemStack(Items.DIAMOND, 999));
        ItemStack privateItem = new ItemStack(Items.PAPER, 41);
        privateItem.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Private storage contents"));
        bag.setItem(5, privateItem);
        bag.updateSettings(tag -> { tag.putInt("display_slot", 0); tag.putInt("display_rotation", 90); tag.putString("last_search", "private query"); });
        block.setStack(bag.stack());
        var update = block.getUpdateTag(helper.getLevel().registryAccess());
        ItemStack visible = com.kadamitas.fabricatedbackpacks.compat.ItemStackTemplate.CODEC.parse(net.minecraft.resources.RegistryOps.create(
                net.minecraft.nbt.NbtOps.INSTANCE, helper.getLevel().registryAccess()), update.get("backpack")).getOrThrow().create();
        helper.assertFalse(visible.has(BagComponents.UPGRADES), "Chunk observers do not receive physical upgrade contents");
        var snapshot = visible.get(BagComponents.CONTENTS);
        helper.assertValueEqual(snapshot.entries().size(), 1, "Appearance packet contains only the selected display item");
        helper.assertValueEqual(snapshot.entries().getFirst().count(), 1, "Display count is normalized so automation does not spam appearance updates");
        helper.assertFalse(update.toString().contains("Private storage contents") || update.toString().contains("private query"), "Appearance sync excludes other items and private navigation state");
        helper.assertValueEqual(count(block, Items.DIAMOND), 999, "Sanitizing network appearance does not mutate saved contents");
        helper.assertValueEqual(count(block, Items.PAPER), 41, "Non-displayed storage remains unchanged");
        var nested = bag(BackpackTier.LEATHER);
        nested.setItem(0, privateItem.copy());
        var secret = new net.minecraft.nbt.CompoundTag();
        secret.putString("secret", "hidden custom payload");
        nested.stack().set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(secret));
        bag.setItem(0, ItemStack.EMPTY);
        bag.remember(0, nested.stack());
        bag.stack().set(com.kadamitas.fabricatedbackpacks.world.WorldComponents.EXTRA_ITEMS,
                new com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot(1, java.util.List.of(
                        new com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot.Entry(0, com.kadamitas.fabricatedbackpacks.compat.ItemStackTemplate.fromNonEmptyStack(privateItem), 41))));
        bag.stack().set(net.minecraft.core.component.DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(secret));
        var expected = bag.stack().copy();
        block.setStack(bag.stack());
        var nestedUpdate = block.getUpdateTag(helper.getLevel().registryAccess());
        helper.assertFalse(nestedUpdate.toString().contains("hidden custom payload") || nestedUpdate.toString().contains("Private storage contents")
                || nestedUpdate.toString().contains("extra_items") || nestedUpdate.toString().contains("identity"),
                "Even a displayed nested bag and modded opaque state cannot leak private storage through appearance packets");
        var publicBag = com.kadamitas.fabricatedbackpacks.item.BackpackVisuals.snapshot(bag.stack());
        helper.assertTrue(com.kadamitas.fabricatedbackpacks.item.BackpackDisplay.from(publicBag).orElseThrow().icon().is(nested.stack().getItem()),
                "A remembered nested backpack still has a usable public display icon");
        assertStack(helper, bag.stack(), expected, "The appearance allowlist never rewrites the authoritative source");
        helper.succeed();
    }

    static void placementSaveAndDrops(GameTestHelper helper) {
        var level = helper.getLevel();
        var player = player(helper);
        player.setShiftKeyDown(true);
        player.setYRot(180);
        int tested = 0;
        for (BackpackTier tier : BackpackTier.values()) {
            BlockPos position = helper.absolutePos(new BlockPos(1 + tested % 3 * 2, 1, 1 + tested / 3 * 2));
            BagInventory bag = bag(tier, UpgradeKind.STACK_UPGRADE_TIER_4);
            bag.setItem(0, new ItemStack(Items.DIAMOND, 100 + tested));
            bag.remember(1, new ItemStack(Items.EMERALD));
            bag.dye(0x125678, 0xfedcba);
            ItemStack expected = bag.stack().copy();
            boolean wet = tier == BackpackTier.NETHERITE;
            if (wet) level.setBlockAndUpdate(position, Blocks.WATER.defaultBlockState());
            player.setItemInHand(InteractionHand.MAIN_HAND, bag.stack());
            var hit = new BlockHitResult(Vec3.atCenterOf(position.below()).add(0, .5, 0), Direction.UP, position.below(), false);
            player.getMainHandItem().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            helper.assertTrue(level.getBlockState(position).is(BackpackRegistry.block(tier)), "Actual item use places " + tier);
            helper.assertTrue(player.getMainHandItem().isEmpty(), "Placement consumes exactly the one held bag");
            var state = level.getBlockState(position);
            helper.assertValueEqual(state.getValue(BackpackBlock.FACING), Direction.SOUTH, "Placed backpack faces the player");
            helper.assertValueEqual(state.getValue(BackpackBlock.WATERLOGGED), wet, "Placement preserves the water state");
            if (wet) helper.assertTrue(state.getFluidState().is(Fluids.WATER), "Waterlogged backpack contains the vanilla water source state");
            var block = (BackpackBlockEntity) level.getBlockEntity(position);
            assertStack(helper, block.stack(), expected, "Placement retains contents, upgrades, memory, and dyes");
            var loaded = (BackpackBlockEntity) BlockEntity.loadStatic(position, state, block.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
            helper.assertTrue(loaded != null, "Backpack block entity loads through its registered type");
            assertStack(helper, loaded.stack(), expected, "Block entity save/load retains the exact backpack item");
            var picked = state.getBlock().getCloneItemStack(level, position, state);
            // Native 1.21 Ctrl-pick adds components through the block entity after cloning its item.
            block.saveToItem(picked, level.registryAccess());
            helper.assertFalse(picked.has(BagComponents.IDENTITY), "Creative clone cannot duplicate a live backpack identity");
            helper.assertValueEqual(count(BagInventory.of(picked), Items.DIAMOND), 100 + tested, "Pick-block preserves requested item data");
            assertPouchRayHits(helper, position);
            level.destroyBlock(position, true, player);
            var dropped = level.getEntitiesOfClass(ItemEntity.class, new AABB(position).inflate(.1));
            helper.assertValueEqual(dropped.size(), 1, "Breaking produces exactly one backpack, without spilling duplicated contents");
            assertStack(helper, dropped.getFirst().getItem(), expected, "Break loot preserves the actual placed bag");
            dropped.getFirst().discard();
            tested++;
        }
        helper.assertValueEqual(tested, 6, "All six tier block/item pairs were exercised");
        helper.succeed();
    }

    private static boolean sameShape(net.minecraft.world.phys.shapes.VoxelShape first, net.minecraft.world.phys.shapes.VoxelShape second) {
        return !Shapes.joinIsNotEmpty(first, second, net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME);
    }

    private static void assertPouchRayHits(GameTestHelper helper, BlockPos position) {
        var level = helper.getLevel();
        var original = level.getBlockState(position);
        try {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                var closed = original.setValue(BackpackBlock.FACING, facing).setValue(BackpackBlock.OPEN, false);
                var outline = closed.getShape(level, position);
                var collision = closed.getCollisionShape(level, position);
                helper.assertTrue(sameShape(outline, collision), "Selection and collision cover the same closed backpack");
                for (boolean open : new boolean[]{false, true}) {
                    var state = closed.setValue(BackpackBlock.OPEN, open);
                    level.setBlockAndUpdate(position, state);
                    helper.assertTrue(sameShape(outline, state.getShape(level, position))
                                    && sameShape(collision, state.getCollisionShape(level, position)),
                            "Opening the rendered lid does not move selection or collision in " + facing);
                    for (ClipContext.Block mode : new ClipContext.Block[]{ClipContext.Block.OUTLINE, ClipContext.Block.COLLIDER}) {
                        assertShapeHit(helper, position, facing, mode, new Vec3(-4, 5.25, 8.25),
                                new Vec3(2, 5.25, 8.25), new Vec3(.75, 5.25, 8.25));
                        assertShapeHit(helper, position, facing, mode, new Vec3(20, 5.25, 8.25),
                                new Vec3(14, 5.25, 8.25), new Vec3(15.25, 5.25, 8.25));
                        assertShapeHit(helper, position, facing, mode, new Vec3(8, 4.25, -4),
                                new Vec3(8, 4.25, 2.5), new Vec3(8, 4.25, 1.5));
                        var miss = level.clip(new ClipContext(modelPoint(position, facing, new Vec3(1.5, 14, 8.25)),
                                modelPoint(position, facing, new Vec3(1.5, 8, 8.25)), mode, ClipContext.Fluid.NONE, CollisionContext.empty()));
                        helper.assertValueEqual(miss.getType(), HitResult.Type.MISS,
                                "Empty space above a side pouch is not a solid oversized box: " + facing + " " + mode);
                    }
                }
            }
        } finally {
            level.setBlockAndUpdate(position, original);
        }
    }

    private static void assertShapeHit(GameTestHelper helper, BlockPos position, Direction facing,
                                       ClipContext.Block mode, Vec3 from, Vec3 to, Vec3 expected) {
        var hit = helper.getLevel().clip(new ClipContext(modelPoint(position, facing, from),
                modelPoint(position, facing, to), mode, ClipContext.Fluid.NONE, CollisionContext.empty()));
        String context = facing + " " + mode + " from " + from;
        helper.assertValueEqual(hit.getType(), HitResult.Type.BLOCK, "The enlarged pouch is ray-targetable: " + context);
        helper.assertValueEqual(hit.getBlockPos(), position, "The ray hits this placed backpack: " + context);
        helper.assertTrue(hit.getLocation().distanceToSqr(modelPoint(position, facing, expected)) < 1e-12,
                "The hit reaches the rotated pouch surface, not the old narrow shell: " + context);
    }

    private static Vec3 modelPoint(BlockPos position, Direction facing, Vec3 point) {
        // Independent facing basis; do not derive expected rays from the shape
        // rotation helper used by production code.
        Direction right = facing.getClockWise();
        double horizontal = (point.x - 8) / 16;
        double forward = (8 - point.z) / 16;
        return Vec3.atLowerCornerOf(position).add(
                .5 + right.getStepX() * horizontal + facing.getStepX() * forward,
                point.y / 16,
                .5 + right.getStepZ() * horizontal + facing.getStepZ() * forward);
    }

    static void viewersPickupAndComparator(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos position = helper.absolutePos(new BlockPos(3, 1, 3));
        level.setBlockAndUpdate(position, BackpackRegistry.block(BackpackTier.LEATHER).defaultBlockState());
        var block = (BackpackBlockEntity) level.getBlockEntity(position);
        BagInventory original = bag(BackpackTier.LEATHER, UpgradeKind.STACK_UPGRADE_TIER_1);
        block.setStack(original.stack());
        helper.assertValueEqual(level.getBlockState(position).getAnalogOutputSignal(level, position), 0, "Empty storage comparator is zero");
        for (int slot = 0; slot < block.getContainerSize(); slot++) block.setItem(slot, new ItemStack(Items.COBBLESTONE, 128));
        helper.assertValueEqual(level.getBlockState(position).getAnalogOutputSignal(level, position), 15, "Comparator measures upgraded capacity, not vanilla stack size");
        var first = player(helper);
        var second = player(helper);
        first.setShiftKeyDown(false);
        second.setShiftKeyDown(false);
        var hit = new BlockHitResult(Vec3.atCenterOf(position), Direction.NORTH, position, false);
        level.getBlockState(position).useWithoutItem(level, first, hit);
        level.getBlockState(position).useWithoutItem(level, second, hit);
        helper.assertTrue(first.containerMenu instanceof BackpackMenu && second.containerMenu instanceof BackpackMenu, "Actual block use opens both server menus");
        helper.assertValueEqual(block.viewers(), 2, "Placed bag tracks both independent viewers");
        helper.assertTrue(level.getBlockState(position).getValue(BackpackBlock.OPEN), "Open block state follows live viewers");
        first.closeContainer();
        helper.assertValueEqual(block.viewers(), 1, "Closing one viewer does not close the other");
        first.setShiftKeyDown(true);
        level.getBlockState(position).useWithoutItem(level, first, hit);
        helper.assertTrue(level.getBlockEntity(position) == block && first.getMainHandItem().isEmpty(), "A backpack being viewed cannot be picked up");
        first.closeContainer();
        second.closeContainer();
        helper.assertValueEqual(block.viewers(), 0, "All closed menus release their viewer leases");
        helper.assertFalse(level.getBlockState(position).getValue(BackpackBlock.OPEN), "Last close clears the open state");
        ItemStack expected = block.stack().copy();
        level.getBlockState(position).useWithoutItem(level, first, hit);
        helper.assertFalse(level.getBlockState(position).is(BackpackRegistry.block(BackpackTier.LEATHER)), "Sneaking with an empty hand removes the placed block");
        assertStack(helper, first.getMainHandItem(), expected, "Pickup returns the same filled bag to the hand");
        helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(position)).isEmpty(), "Hand pickup does not also drop another bag");
        helper.succeed();
    }

    static void naturalHopperTransfers(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
        level.setBlockAndUpdate(position, BackpackRegistry.block(BackpackTier.LEATHER).defaultBlockState());
        level.setBlockAndUpdate(position.above(), Blocks.HOPPER.defaultBlockState());
        level.setBlockAndUpdate(position.below(), Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(position.below().east(), Blocks.CHEST.defaultBlockState());
        var block = (BackpackBlockEntity) level.getBlockEntity(position);
        block.setStack(bag(BackpackTier.LEATHER, UpgradeKind.FILTER).stack());
        BagInventory bag = block.inventory();
        var filter = upgrade(bag, 0);
        bag.setFilter(filter, 0, new ItemStack(Items.DIAMOND));
        bag.updateSettings(filter, state -> { state.putString("filter_mode", "ALLOW"); state.putString("filter_direction", "INPUT"); });
        var upper = (HopperBlockEntity) level.getBlockEntity(position.above());
        var lower = (HopperBlockEntity) level.getBlockEntity(position.below());
        var chest = (ChestBlockEntity) level.getBlockEntity(position.below().east());
        upper.setItem(0, new ItemStack(Items.DIRT, 3));
        upper.setItem(1, new ItemStack(Items.DIAMOND, 2));
        helper.runAfterDelay(40, () -> {
            helper.assertValueEqual(count(upper, Items.DIRT), 3, "Natural hopper input leaves disallowed items in its source");
            helper.assertValueEqual(count(chest, Items.DIAMOND), 2, "Natural block ticks transfer accepted items through both hoppers");
            helper.assertValueEqual(count(upper, Items.DIAMOND) + count(bag, Items.DIAMOND) + count(lower, Items.DIAMOND), 0, "Both transferred items leave their earlier containers");
            bag.setItem(0, new ItemStack(Items.DIAMOND, 5));
            bag.updateSettings(filter, state -> { state.putString("filter_mode", "BLOCK"); state.putString("filter_direction", "OUTPUT"); });
        });
        helper.runAfterDelay(65, () -> {
            helper.assertValueEqual(count(bag, Items.DIAMOND), 5, "Output filter prevents naturally ticking hopper extraction");
            helper.assertValueEqual(count(chest, Items.DIAMOND), 2, "Rejected output never appears in the destination");
            bag.updateSettings(filter, state -> state.putBoolean("enabled", false));
        });
        helper.runAfterDelay(125, () -> {
            helper.assertValueEqual(count(chest, Items.DIAMOND), 7, "Disabling the output filter releases exactly the five remaining items");
            helper.assertValueEqual(count(bag, Items.DIAMOND) + count(lower, Items.DIAMOND) + count(upper, Items.DIAMOND), 0, "Completed transfers conserve all seven diamonds");
            helper.succeed();
        });
    }
}
