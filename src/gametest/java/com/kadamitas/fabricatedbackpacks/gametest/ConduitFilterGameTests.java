package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitBundleBlockEntity;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilter;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilterAction;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilterMode;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitFilterState;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMenu;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Optional;

import static com.kadamitas.fabricatedbackpacks.gametest.BackpackTestSupport.*;

/** Real server packet dispatch and persistence; the connected recipients are explicitly embedded fixtures. */
public final class ConduitFilterGameTests {
    private ConduitFilterGameTests() {}
    private record Opened(ServerPlayer player, ConduitBundleBlockEntity entity, ConduitMenu menu, Vec3 position) {}

    public static void menuAuthority(GameTestHelper helper) {
        Opened opened = open(helper);
        var player = opened.player();
        var entity = opened.entity();
        var menu = opened.menu();
        var foreign = player(helper);
        player.getInventory().setItem(9, new ItemStack(Items.DIAMOND, 7));
        menu.setCarried(new ItemStack(Items.DIAMOND, 5));
        send(player, entry(menu, ConduitKind.ITEM, 8, "minecraft:cobblestone"));
        send(player, mode(menu, ConduitKind.ITEM, ConduitFilterMode.ALLOW));
        send(player, entry(menu, ConduitKind.FLUID, 4, "minecraft:flowing_water"));
        send(player, mode(menu, ConduitKind.FLUID, ConduitFilterMode.BLOCK));
        helper.runAfterDelay(2, () -> {
            helper.assertValueEqual(entity.filter(ConduitKind.ITEM, Direction.EAST),
                    new ConduitFilter(ConduitFilterMode.ALLOW, Map.of(8, ResourceLocation.withDefaultNamespace("cobblestone"))),
                    "The actual Fabric receiver changes the requested ghost on the physically opened face");
            helper.assertValueEqual(entity.filter(ConduitKind.FLUID, Direction.EAST),
                    new ConduitFilter(ConduitFilterMode.BLOCK, Map.of(4, ResourceLocation.withDefaultNamespace("water"))),
                    "Flowing fluid requests resolve to their registered source fluid identity");
            helper.assertValueEqual(entity.filter(ConduitKind.ITEM, Direction.NORTH), ConduitFilter.EMPTY,
                    "No packet field can redirect the action to another face");
            helper.assertValueEqual(menu.getCarried().getCount(), 5, "Registry ghost edits never consume a real cursor stack");
            var expected = entity.saveWithFullMetadata(helper.getLevel().registryAccess());
            send(player, entry(menu, ConduitKind.ITEM, 0, "not_installed:unknown_item"));
            send(player, entry(menu, ConduitKind.ITEM, 0, "minecraft:air"));
            send(player, entry(menu, ConduitKind.ITEM, 0, "minecraft:cobblestone"));
            send(player, entry(menu, ConduitKind.FLUID, 0, "minecraft:empty"));
            send(player, new ConduitFilterAction(menu.containerId + 1, ConduitKind.ITEM,
                    ConduitFilterAction.Operation.CLEAR_ENTRY, 8, Optional.empty()));
            send(foreign, mode(menu, ConduitKind.ITEM, ConduitFilterMode.OFF));
            foreign.containerMenu = menu;
            helper.assertFalse(menu.applyFilterAction(foreign, mode(menu, ConduitKind.ITEM, ConduitFilterMode.OFF)),
                    "Even a foreign player pointing at the exact menu object cannot edit its owner's policy");
            foreign.containerMenu = foreign.inventoryMenu;
            player.setGameMode(GameType.SPECTATOR);
            helper.assertFalse(menu.applyFilterAction(player, mode(menu, ConduitKind.ITEM, ConduitFilterMode.OFF)),
                    "A spectator cannot edit an otherwise current menu");
            player.setGameMode(GameType.SURVIVAL);
            helper.runAfterDelay(2, () -> {
                helper.assertValueEqual(entity.saveWithFullMetadata(helper.getLevel().registryAccess()), expected,
                        "Unknown, empty, duplicate, wrong-menu and foreign requests leave exact persisted data untouched");
                player.setPos(opened.position().add(20, 0, 0));
                send(player, mode(menu, ConduitKind.ITEM, ConduitFilterMode.OFF));
                helper.runAfterDelay(2, () -> {
                    helper.assertValueEqual(entity.saveWithFullMetadata(helper.getLevel().registryAccess()), expected,
                            "Moving outside the physical interface's range rejects queued edits");
                    player.closeContainer();
                    player.setPos(opened.position());
                    ConduitMenus.open(player, entity, Direction.EAST);
                    var stale = (ConduitMenu) player.containerMenu;
                    var replacement = new ConduitBundleBlockEntity(entity.getBlockPos(), entity.getBlockState());
                    helper.getLevel().removeBlockEntity(entity.getBlockPos());
                    helper.getLevel().setBlockEntity(replacement);
                    for (ConduitKind kind : ConduitKind.values()) replacement.install(kind);
                    send(player, entry(stale, ConduitKind.ITEM, 0, "minecraft:iron_ingot"));
                    helper.runAfterDelay(2, () -> {
                        helper.assertValueEqual(replacement.filter(ConduitKind.ITEM, Direction.EAST), ConduitFilter.EMPTY,
                                "A same-position replacement cannot inherit an old menu's mutation");
                        player.closeContainer();
                        ConduitMenus.open(player, replacement, Direction.EAST);
                        helper.assertTrue(player.containerMenu.containerId != stale.containerId, "The reopened menu has a new identity");
                        send(player, entry(stale, ConduitKind.ITEM, 0, "minecraft:iron_ingot"));
                        helper.runAfterDelay(2, () -> {
                            helper.assertValueEqual(replacement.filter(ConduitKind.ITEM, Direction.EAST), ConduitFilter.EMPTY,
                                    "A closed-session replay cannot edit the new live menu");
                            player.closeContainer();
                            helper.assertValueEqual(count(player.getInventory(), Items.DIAMOND), 12,
                                    "Every accepted and rejected ghost edit conserves inventory plus cursor items");
                            helper.succeed();
                        });
                    });
                });
            });
        });
    }

    public static void persistedPolicyValidationAndViewerState(GameTestHelper helper) {
        Opened opened = open(helper);
        var entity = opened.entity();
        var policy = new ConduitFilter(ConduitFilterMode.BLOCK, Map.of(8, ResourceLocation.parse("removed_mod:fluid_or_item")));
        entity.setFilter(ConduitKind.ITEM, Direction.EAST, policy);
        var saved = entity.saveWithFullMetadata(helper.getLevel().registryAccess());
        var restored = (ConduitBundleBlockEntity) BlockEntity.loadStatic(entity.getBlockPos(), entity.getBlockState(),
                saved, helper.getLevel().registryAccess());
        helper.assertTrue(restored != null, "The saved conduit is decoded through the registered block-entity loader");
        helper.assertValueEqual(restored.filter(ConduitKind.ITEM, Direction.EAST), policy,
                "A removed mod's syntactically valid identities survive a real save/load");
        helper.assertValueEqual(restored.filter(ConduitKind.FLUID, Direction.EAST), ConduitFilter.EMPTY,
                "An absent legacy policy is unrestricted");
        var damaged = saved.copy();
        damaged.putString("item_filter_east", "not a policy");
        var failClosed = (ConduitBundleBlockEntity) BlockEntity.loadStatic(entity.getBlockPos(), entity.getBlockState(),
                damaged, helper.getLevel().registryAccess());
        helper.assertTrue(failClosed != null, "A bad policy does not erase the rest of a saved conduit");
        helper.assertValueEqual(failClosed.filter(ConduitKind.ITEM, Direction.EAST), ConduitFilter.DENY_ALL,
                "A present malformed policy denies transfers instead of silently opening its interface");
        helper.assertValueEqual(failClosed.filter(ConduitKind.FLUID, Direction.EAST), ConduitFilter.EMPTY,
                "One damaged policy does not modify another resource kind");
        helper.assertFalse(entity.setFilter(ConduitKind.ENERGY, Direction.EAST, ConduitFilter.DENY_ALL),
                "Energy cannot acquire an item/fluid restriction");
        helper.assertFalse(entity.getUpdateTag(helper.getLevel().registryAccess()).contains("item_filter_east"),
                "Chunk observers are not sent menu-only policy data");
        helper.assertValueEqual(opened.menu().filterState().itemFilter(), policy,
                "An authorized viewer receives only its current face's public policy");

        // This is the common client-mirror model, not a claim of rendered client timing or TCP coverage.
        var mirror = new ConduitMenu(opened.menu().containerId, opened.player().getInventory(), entity.getBlockPos());
        var snapshot = new ConduitFilterState(mirror.containerId, Direction.SOUTH, policy, ConduitFilter.DENY_ALL);
        mirror.applyFilters(snapshot);
        helper.assertValueEqual(mirror.selectedFace(), Direction.SOUTH,
                "An initial filter snapshot is accepted before the placeholder NORTH face DataSlot changes");
        helper.assertValueEqual(mirror.filter(ConduitKind.ITEM), policy, "The initial item filter is not lost in that ordering");
        helper.assertValueEqual(mirror.filter(ConduitKind.FLUID), ConduitFilter.DENY_ALL, "Both policies are applied together");
        mirror.applyFilters(new ConduitFilterState(mirror.containerId + 1, Direction.WEST, ConduitFilter.EMPTY, ConduitFilter.EMPTY));
        helper.assertValueEqual(mirror.filterState(), snapshot, "A snapshot for another container is ignored");
        opened.player().closeContainer();
        helper.succeed();
    }

    private static Opened open(GameTestHelper helper) {
        BlockPos position = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.getLevel().setBlockAndUpdate(position.east(), Blocks.CHEST.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(position, AutomationRegistry.CONDUIT_BUNDLE.defaultBlockState());
        var entity = (ConduitBundleBlockEntity) helper.getLevel().getBlockEntity(position);
        for (ConduitKind kind : ConduitKind.values()) entity.install(kind);
        var player = player(helper);
        Vec3 standing = Vec3.atLowerCornerOf(position).add(.5, 0, -1.5);
        player.setPos(standing);
        entity.refreshVisual();
        helper.assertTrue(entity.visualState().endpoint(ConduitKind.ITEM, Direction.EAST), "The fixture supplies a physical east item interface");
        ConduitMenus.open(player, entity, Direction.EAST);
        helper.assertTrue(player.containerMenu instanceof ConduitMenu, "The native menu opens for its nearby owner");
        return new Opened(player, entity, (ConduitMenu) player.containerMenu, standing);
    }
    private static ConduitFilterAction entry(ConduitMenu menu, ConduitKind kind, int row, String id) {
        return new ConduitFilterAction(menu.containerId, kind, ConduitFilterAction.Operation.SET_ENTRY, row,
                Optional.of(ResourceLocation.parse(id)));
    }
    private static ConduitFilterAction mode(ConduitMenu menu, ConduitKind kind, ConduitFilterMode mode) {
        return new ConduitFilterAction(menu.containerId, kind, ConduitFilterAction.Operation.SET_MODE, mode.ordinal(), Optional.empty());
    }
    private static void send(ServerPlayer player, ConduitFilterAction request) {
        player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(request));
    }
}
