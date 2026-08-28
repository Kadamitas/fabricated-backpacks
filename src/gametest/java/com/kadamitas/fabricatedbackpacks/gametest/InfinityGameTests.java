package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenu;
import com.kadamitas.fabricatedbackpacks.menu.BackpackMenus;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import java.util.List;
import java.util.Optional;

/** Infinity is an explicit source of generated items; its finite seed must never be consumed or replaced. */
public final class InfinityGameTests {
    private InfinityGameTests() {}

    private static BagInventory plain() { return BackpackTestSupport.bag(BackpackTier.NETHERITE); }
    private static ItemStack upgrade(UpgradeKind kind) { return new ItemStack(BackpackRegistry.item(kind)); }
    private static void gameMaster(ServerPlayer player) {
        player.level().getServer().getPlayerList().op(player.nameAndId(), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.of(false));
    }
    private static void ordinary(ServerPlayer player) { player.level().getServer().getPlayerList().deop(player.nameAndId()); }
    private static BagInventory seededFixture(UpgradeKind infinity) {
        BagInventory bag = plain();
        // Fixture setup is trusted; player permission is exercised through actual menus below.
        bag.upgrades().setItem(0, upgrade(infinity));
        return bag;
    }

    public static void seedRules(GameTestHelper helper) {
        ServerPlayer operator = BackpackTestSupport.player(helper);
        gameMaster(operator);
        try {
            helper.assertTrue(operator.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER), "Fixture has the actual game-master permission");
            for (UpgradeKind infinity : List.of(UpgradeKind.INFINITY, UpgradeKind.SURVIVAL_INFINITY)) {
                BagInventory bag = seededFixture(infinity);
                ItemStack seed = new ItemStack(Items.DIAMOND, 3);
                seed.set(DataComponents.CUSTOM_NAME, Component.literal("Finite original"));
                bag.setItem(7, seed);
                helper.assertFalse(bag.canPlaceItem(7, seed, operator), "Established seed rejects even identical insertion");
                helper.assertTrue(bag.canPlaceItem(8, seed, operator), "An eligible empty slot can receive a new seed");
                helper.assertValueEqual(bag.removeItem(7, 64).getCount(), 64, "Extraction produces its requested quantity");
                helper.assertValueEqual(bag.removeItem(7, 1).getCount(), 1, "Single-item extraction is also nondepleting");
                bag.getItem(7).setCount(0);
                bag.setItem(7, new ItemStack(Items.EMERALD));
                bag.clearContent();
                bag.sort("name");
                BackpackTestSupport.assertStack(helper, bag.getItem(7), seed, "Direct mutable reads, replacement, clearing and sorting cannot change a seed");
                BagInventory restored = BagInventory.of(BackpackTestSupport.roundTrip(helper.getLevel(), bag.stack()));
                BackpackTestSupport.assertStack(helper, restored.getItem(7), seed, "Saving retains the finite seed and its components");
                BagInventory mirror = BagInventory.of(bag.stack().copy());
                mirror.markClientMirror();
                mirror.setItem(7, ItemStack.EMPTY);
                helper.assertTrue(mirror.getItem(7).isEmpty(), "Client mirror accepts an authoritative correction of a predicted seed");
                BackpackTestSupport.assertStack(helper, bag.getItem(7), seed, "Client correction cannot mutate the authoritative server seed");
                for (UpgradeKind other : UpgradeKind.values()) {
                    if (other.family().equals("infinity")) continue;
                    helper.assertFalse(bag.canInstall(1, upgrade(other), operator), "Infinity rejects other upgrade " + other);
                    BagInventory reverse = plain();
                    reverse.upgrades().setItem(0, upgrade(other));
                    helper.assertFalse(reverse.canInstall(1, upgrade(infinity), operator), "Reverse insertion order rejects " + other + " with " + infinity);
                }
                helper.assertTrue(restored.canRemoveUpgrade(0, operator), "Authorized removal can recover the underlying finite inventory");
                restored.upgrades().setItem(0, ItemStack.EMPTY);
                helper.assertValueEqual(restored.removeItem(7, 64).getCount(), 3, "Removing infinity recovers only the three original seed items");
                helper.assertTrue(restored.getItem(7).isEmpty(), "Recovered finite seed depletes normally");
            }
        } finally { ordinary(operator); }
        helper.succeed();
    }

    public static void menuPermissionsAndExtraction(GameTestHelper helper) {
        ServerPlayer player = BackpackTestSupport.player(helper);
        BagInventory bag = plain();
        player.getInventory().setItem(0, bag.stack());
        BackpackMenus.openInventory(player, 0);
        BackpackMenu menu = (BackpackMenu) player.containerMenu;
        int upgradeSlot = bag.getContainerSize();
        try {
            helper.assertFalse(player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER), "Ordinary fixture has no operator permission");
            menu.setCarried(upgrade(UpgradeKind.INFINITY));
            menu.clicked(upgradeSlot, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(bag.upgrades().getItem(0).isEmpty(), "Nonoperator cannot install administrator infinity");
            helper.assertTrue(menu.getCarried().is(BackpackRegistry.item(UpgradeKind.INFINITY)), "Denied installation leaves the actual cursor upgrade intact");
            player.setGameMode(GameType.CREATIVE);
            menu.clicked(upgradeSlot, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(bag.upgrades().getItem(0).isEmpty(), "Creative mode alone does not confer game-master permission");
            player.setGameMode(GameType.SURVIVAL);
            gameMaster(player);
            menu.clicked(upgradeSlot, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(bag.has(UpgradeKind.INFINITY) && menu.getCarried().isEmpty(), "Game-master permission works while the operator is in survival mode");
            menu.setCarried(new ItemStack(Items.EMERALD, 5));
            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            helper.assertValueEqual(bag.getItem(0).getCount(), 5, "Operator seeds the slot through the real menu");
            helper.assertTrue(menu.getCarried().isEmpty(), "Seeding consumes only the actual finite cursor supply");
            ordinary(player);
            menu.setCarried(new ItemStack(Items.DIAMOND, 2));
            menu.clicked(1, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(bag.getItem(1).isEmpty(), "A nonoperator cannot seed another admin-infinity slot");
            helper.assertValueEqual(menu.getCarried().getCount(), 2, "Denied seeding preserves the cursor quantity");
            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(bag.getItem(0).is(Items.EMERALD) && menu.getCarried().is(Items.DIAMOND), "Different cursor item cannot replace an established seed");
            menu.setCarried(ItemStack.EMPTY);
            menu.clicked(upgradeSlot, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(bag.has(UpgradeKind.INFINITY), "An ordinary player cannot remove administrator infinity");
            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            helper.assertValueEqual(menu.getCarried().getCount(), 64, "Any player can extract a normal full cursor stack from an authorized seed");
            menu.setCarried(new ItemStack(Items.EMERALD, 63));
            menu.clicked(0, 1, ContainerInput.PICKUP, player);
            helper.assertValueEqual(menu.getCarried().getCount(), 64, "Right-click adds one without exceeding the item's normal limit");
            menu.setCarried(new ItemStack(Items.EMERALD, 65));
            menu.clicked(0, 0, ContainerInput.PICKUP, player);
            helper.assertValueEqual(menu.getCarried().getCount(), 65, "An already oversized cursor is never silently clamped or discarded");
            menu.setCarried(ItemStack.EMPTY);
            int before = BackpackTestSupport.count(player.getInventory(), Items.EMERALD);
            menu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);
            helper.assertValueEqual(BackpackTestSupport.count(player.getInventory(), Items.EMERALD) - before, 64, "One shift-click generates one bounded batch");
            player.getInventory().setItem(1, ItemStack.EMPTY);
            menu.clicked(0, 1, ContainerInput.SWAP, player);
            helper.assertValueEqual(player.getInventory().getItem(1).getCount(), 64, "Hotbar extraction uses a normal stack limit");
            player.getInventory().setItem(1, new ItemStack(Items.DIAMOND, 3));
            menu.clicked(0, 1, ContainerInput.SWAP, player);
            helper.assertTrue(player.getInventory().getItem(1).is(Items.DIAMOND), "Hotbar replacement never discards a different item");
            helper.assertValueEqual(bag.getItem(0).getCount(), 5, "All cursor, shift and hotbar operations leave the finite seed untouched");
            gameMaster(player);
            menu.clicked(upgradeSlot, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(bag.infinityKind() == null, "Authorized operator can remove the infinity upgrade");
            helper.assertValueEqual(bag.getItem(0).getCount(), 5, "Removing infinity reveals only the original five emeralds");
            ordinary(player);
            player.closeContainer();

            BagInventory survival = plain();
            player.getInventory().setItem(0, survival.stack());
            BackpackMenus.openInventory(player, 0);
            BackpackMenu survivalMenu = (BackpackMenu) player.containerMenu;
            survivalMenu.setCarried(upgrade(UpgradeKind.SURVIVAL_INFINITY));
            survivalMenu.clicked(survival.getContainerSize(), 0, ContainerInput.PICKUP, player);
            helper.assertTrue(survival.has(UpgradeKind.SURVIVAL_INFINITY), "Survival infinity permits ordinary-player installation");
            survivalMenu.setCarried(new ItemStack(Items.DIAMOND));
            survivalMenu.clicked(0, 0, ContainerInput.PICKUP, player);
            helper.assertTrue(survival.isInfiniteSlot(0), "Ordinary player can seed survival infinity");
            survivalMenu.clicked(survival.getContainerSize(), 0, ContainerInput.PICKUP, player);
            helper.assertTrue(survival.infinityKind() == null, "Ordinary player can remove survival infinity");
            ItemStack replacement = survival.stack().copy();
            player.getInventory().setItem(0, replacement);
            helper.assertFalse(survivalMenu.stillValid(player), "Replacing a held source with a UUID-identical copy invalidates its old inventory menu");
        } finally { ordinary(player); }
        helper.succeed();
    }

    public static void automationAndRollback(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.getLevel().setBlockAndUpdate(position, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState());
        BackpackBlockEntity placed = (BackpackBlockEntity) helper.getLevel().getBlockEntity(position);
        placed.setStack(seededFixture(UpgradeKind.SURVIVAL_INFINITY).stack());
        BagInventory bag = placed.inventory();
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(helper.getLevel(), position, Direction.NORTH);
        ItemVariant diamond = ItemVariant.of(Items.DIAMOND);
        try (Transaction outer = Transaction.openOuter()) {
            try (Transaction nested = outer.openNested()) {
                helper.assertValueEqual(storage.insert(diamond, 1, nested), 1L, "Survival automation can insert a finite seed");
                nested.commit();
            }
        }
        helper.assertTrue(bag.getItem(0).isEmpty(), "Outer rollback removes the newly seeded slot despite ordinary seed immutability");
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(storage.insert(diamond, 1, transaction), 1L, "Committed automated seeding consumes exactly one source item");
            transaction.commit();
        }
        var view = storage.iterator().next();
        helper.assertValueEqual(view.getAmount(), Long.MAX_VALUE, "Fabric views advertise an unbounded seeded supply");
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(storage.extract(diamond, Long.MAX_VALUE, transaction), Long.MAX_VALUE, "Unbounded extraction honors a long-sized API request without integer overflow");
            helper.assertValueEqual(view.extract(ItemVariant.of(Items.EMERALD), 1, transaction), 0L, "The infinite view still enforces exact resource identity");
        }
        SimpleContainer destination = new SimpleContainer(1);
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(StorageUtil.move(storage, ContainerStorage.of(destination, null), item -> true, 100, transaction), 64L, "Actual destination capacity constrains infinite output");
        }
        helper.assertTrue(destination.isEmpty(), "Aborting a generated transfer rolls back its destination");
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(StorageUtil.move(storage, ContainerStorage.of(destination, null), item -> true, 100, transaction), 64L, "Committed destination receives exactly its accepted quantity");
            transaction.commit();
        }
        helper.assertValueEqual(destination.getItem(0).getCount(), 64, "Generated stack respects the destination's physical limit");
        helper.assertValueEqual(bag.getItem(0).getCount(), 1, "API operations preserve the one finite seed");
        BlockPos adminPosition = helper.absolutePos(new BlockPos(5, 2, 2));
        helper.getLevel().setBlockAndUpdate(adminPosition, BackpackRegistry.block(BackpackTier.NETHERITE).defaultBlockState());
        ((BackpackBlockEntity) helper.getLevel().getBlockEntity(adminPosition)).setStack(seededFixture(UpgradeKind.INFINITY).stack());
        Storage<ItemVariant> admin = ItemStorage.SIDED.find(helper.getLevel(), adminPosition, null);
        try (Transaction transaction = Transaction.openOuter()) {
            helper.assertValueEqual(admin.insert(diamond, 1, transaction), 0L, "An actorless API cannot bypass administrator seed permission");
        }
        BlockPos hopperPosition = position.below();
        helper.getLevel().setBlockAndUpdate(hopperPosition, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.EAST));
        helper.getLevel().setBlockAndUpdate(hopperPosition.east(), Blocks.CHEST.defaultBlockState());
        HopperBlockEntity hopper = (HopperBlockEntity) helper.getLevel().getBlockEntity(hopperPosition);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(hopperPosition.east());
        helper.runAfterDelay(41, () -> {
            int produced = BackpackTestSupport.count(hopper, Items.DIAMOND) + BackpackTestSupport.count(chest, Items.DIAMOND);
            helper.assertTrue(produced > 1, "Repeated natural hopper ticks produce more items than the finite seed contains");
            helper.assertValueEqual(bag.getItem(0).getCount(), 1, "Natural hopper extraction cannot deplete the seed");
            helper.getLevel().setBlockAndUpdate(hopperPosition, helper.getLevel().getBlockState(hopperPosition).setValue(HopperBlock.ENABLED, false));
            bag.upgrades().setItem(0, ItemStack.EMPTY);
            helper.assertValueEqual(view.getAmount(), 1L, "Existing API views notice infinity removal immediately");
            try (Transaction transaction = Transaction.openOuter()) {
                helper.assertValueEqual(storage.extract(diamond, 100, transaction), 1L, "After removal, only the finite original seed can be extracted");
                transaction.commit();
            }
            helper.assertTrue(bag.getItem(0).isEmpty(), "Finite extraction depletes the recovered original normally");
            helper.succeed();
        });
    }
}
