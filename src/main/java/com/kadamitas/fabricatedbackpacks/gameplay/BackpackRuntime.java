package com.kadamitas.fabricatedbackpacks.gameplay;

import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import com.kadamitas.fabricatedbackpacks.upgrade.UpgradeEngine;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BackpackRuntime {
    private static final Map<MinecraftServer, Map<ItemStack, BagInventory>> LIVE = new IdentityHashMap<>();
    private BackpackRuntime() {}
    public static void initialize() {
        ServerTickEvents.START_SERVER_TICK.register(BackpackIdentities::tick);
        ServerTickEvents.END_SERVER_TICK.register(BackpackRuntime::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { LIVE.remove(server); BackpackTraversal.stop(server); BackpackIdentities.stop(server); UpgradeEngine.stopAll(server); });
        net.fabricmc.fabric.api.event.player.AttackBlockCallback.EVENT.register((player, level, hand, pos, face) -> {
            if (player instanceof ServerPlayer serverPlayer && hand == net.minecraft.world.InteractionHand.MAIN_HAND)
                for (BagInventory bag : carried(serverPlayer)) if (UpgradeEngine.blockAttack(bag, serverPlayer, level.getBlockState(pos), false)) {
                    publishEquipment(serverPlayer, bag);
                    break;
                }
            return net.minecraft.world.InteractionResult.PASS;
        });
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (player instanceof ServerPlayer serverPlayer && entity instanceof net.minecraft.world.entity.LivingEntity target)
                for (BagInventory bag : carried(serverPlayer)) if (UpgradeEngine.entityAttack(bag, serverPlayer, target, false)) {
                    publishEquipment(serverPlayer, bag);
                    break;
                }
            return net.minecraft.world.InteractionResult.PASS;
        });
        for (var tier : com.kadamitas.fabricatedbackpacks.domain.BackpackTier.values()) {
            net.minecraft.world.level.block.DispenserBlock.registerBehavior(BackpackRegistry.item(tier), new net.minecraft.core.dispenser.OptionalDispenseItemBehavior() {
                @Override protected ItemStack execute(net.minecraft.core.dispenser.BlockSource source, ItemStack stack) {
                    var facing = source.state().getValue(net.minecraft.world.level.block.DispenserBlock.FACING);
                    var context = new net.minecraft.world.item.context.DirectionalPlaceContext(source.level(), source.pos().relative(facing), facing, stack, net.minecraft.core.Direction.UP);
                    setSuccess(((com.kadamitas.fabricatedbackpacks.item.BackpackItem) stack.getItem()).place(context).consumesAction());
                    return stack;
                }
            });
        }
    }
    /** The request names only a position; the server derives and authorizes every picked item. */
    public static boolean pickBlock(ServerPlayer player, net.minecraft.core.BlockPos pos) {
        return pickBlock(player, pos, false);
    }
    public static boolean pickBlock(ServerPlayer player, net.minecraft.core.BlockPos pos, boolean includeData) {
        if (!player.isAlive() || player.isSpectator()
                || includeData && (!player.isCreative() || !player.hasPermissions(2))) return false;
        var level = player.serverLevel();
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !level.getWorldBorder().isWithinBounds(pos) || !player.mayInteract(level, pos)) return false;
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange();
        if (!Double.isFinite(reach) || reach < 0 || pos.distToCenterSqr(eye) > (reach + 1) * (reach + 1)) return false;
        Vec3 end = eye.add(player.getViewVector(1).scale(reach));
        // A client request cannot cause the native ray caster to load a neighboring chunk.
        for (int x = net.minecraft.util.Mth.floor(Math.min(eye.x, end.x)) >> 4; x <= (net.minecraft.util.Mth.floor(Math.max(eye.x, end.x)) >> 4); x++)
            for (int z = net.minecraft.util.Mth.floor(Math.min(eye.z, end.z)) >> 4; z <= (net.minecraft.util.Mth.floor(Math.max(eye.z, end.z)) >> 4); z++)
                if (level.getChunkSource().getChunkNow(x, z) == null) return false;
        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, end,
                net.minecraft.world.level.ClipContext.Block.OUTLINE, net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) return false;
        if (includeData) {
            if (!(chunk.getBlockEntities().get(pos) instanceof com.kadamitas.fabricatedbackpacks.block.BackpackBlockEntity entity)
                    || entity.isRemoved()) return false;
            entity.inventory().save();
            ItemStack copy = com.kadamitas.fabricatedbackpacks.storage.BackpackCopies.fork(entity.stack());
            player.getInventory().setPickedItem(copy);
            synchronizePick(player);
            return true;
        }
        var state = chunk.getBlockState(pos);
        ItemStack desired = state.getBlock().getCloneItemStack(level, pos, state);
        if (desired.isEmpty()) return false;
        for (BagInventory bag : carried(player)) if (UpgradeEngine.pickBlock(bag, player, desired)) {
            publishEquipment(player, bag);
            synchronizePick(player);
            return true;
        }
        return false;
    }
    private static void synchronizePick(ServerPlayer player) {
        player.getInventory().setChanged();
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(player.getInventory().selected));
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
    }
    private static void publishEquipment(ServerPlayer player, BagInventory bag) {
        if (BackpackEquipment.isCurrent(player, bag)) BackpackEquipment.setFromInventory(player, bag);
    }
    private static BagInventory handle(ServerPlayer player, ItemStack stack) {
        Map<ItemStack, BagInventory> live = LIVE.computeIfAbsent(player.serverLevel().getServer(), ignored -> new IdentityHashMap<>());
        if (player.containerMenu instanceof com.kadamitas.fabricatedbackpacks.menu.BackpackSessionMenu menu
                && menu.backpack().stack() == stack) {
            // Inventory sessions retain their actual physical source. Equal UUIDs
            // on two independent stacks must not redirect one lease to the other.
            live.put(stack, menu.backpack());
            return menu.backpack();
        }
        BagInventory current = BagInventory.of(stack);
        // This map keeps active handles alive; BagInventory.of is the single source of handle identity.
        live.put(stack, current);
        return current;
    }
    public static List<BagInventory> carried(ServerPlayer player) {
        return roots(player, !com.kadamitas.fabricatedbackpacks.config.BackpackConfig.get().storage().onlyWornUpgrades());
    }
    /** Physical roots are still archived when inventory-bag automation is disabled. */
    public static List<BagInventory> physicalCarried(ServerPlayer player) { return roots(player, true); }
    private static List<BagInventory> roots(ServerPlayer player, boolean includeInventory) {
        List<BagInventory> result = new ArrayList<>();
        Set<ItemStack> physical = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        BackpackEquipment.inventory(player).ifPresent(bag -> {
            result.add(bag);
            physical.add(bag.stack());
            physical.add(BackpackEquipment.get(player));
            LIVE.computeIfAbsent(player.serverLevel().getServer(), ignored -> new IdentityHashMap<>()).put(bag.stack(), bag);
        });
        if (!includeInventory) return result;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (BackpackRegistry.isBackpack(stack) && physical.add(stack)) result.add(handle(player, stack));
        }
        return result;
    }
    private static void tick(MinecraftServer server) {
        Set<ItemStack> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.isAlive() && !player.isSpectator()) {
                com.kadamitas.fabricatedbackpacks.config.BurdenRuntime.tick(player);
                for (BagInventory bag : carried(player)) {
                    seen.add(bag.stack());
                    var before = bag.stack().getComponentsPatch();
                    BackpackTraversal.tick(bag, player.serverLevel(), player.blockPosition(), player);
                    if (!before.equals(bag.stack().getComponentsPatch()) && BackpackEquipment.isCurrent(player, bag)) {
                        BackpackEquipment.setFromInventory(player, bag);
                    }
                }
            }
            if (server.getTickCount() % 20 == 0) for (BagInventory bag : physicalCarried(player)) {
                seen.add(bag.stack());
                archiveTree(bag, player.serverLevel(), player);
            }
        }
        LIVE.computeIfAbsent(server, ignored -> new IdentityHashMap<>()).keySet().removeIf(stack -> !seen.contains(stack));
        UpgradeEngine.endServerTick(server);
    }
    public static void archiveTree(BagInventory bag, net.minecraft.server.level.ServerLevel level, ServerPlayer owner) {
        for (var child : BackpackTraversal.children(bag))
            com.kadamitas.fabricatedbackpacks.admin.BackpackArchives.record(level, child.inventory(), owner);
        com.kadamitas.fabricatedbackpacks.admin.BackpackArchives.record(level, bag, owner);
    }
    public static void pickup(ItemEntity item, ServerPlayer player) {
        for (BagInventory bag : carried(player)) {
            if (item.isRemoved() || item.getItem().isEmpty()) return;
            UpgradeEngine.pickup(bag, item, player);
            if (BackpackEquipment.isCurrent(player, bag)) BackpackEquipment.setFromInventory(player, bag);
        }
    }
    public static boolean everlasting(ItemStack stack) {
        return BackpackRegistry.isBackpack(stack) && stack.getOrDefault(BagComponents.UPGRADES, InventorySnapshot.EMPTY).items().stream()
                .anyMatch(item -> BackpackRegistry.kind(item).orElse(null) == UpgradeKind.EVERLASTING);
    }
    public static void protectDropped(ItemEntity entity) {
        BackpackIdentities.tickDropped(entity);
        if (!everlasting(entity.getItem())) return;
        entity.setUnlimitedLifetime();
        if (entity.getY() < entity.level().getMinBuildHeight() + 2) {
            int safeY = entity.level().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    entity.blockPosition().getX(), entity.blockPosition().getZ());
            entity.setPos(entity.getX(), Math.max(safeY + 1, entity.level().getMinBuildHeight() + 4), entity.getZ());
            entity.setDeltaMovement(Vec3.ZERO);
        } else if (entity.isInWater() || entity.isInLava()) {
            Vec3 velocity = entity.getDeltaMovement();
            entity.setDeltaMovement(velocity.x * 0.9, Math.max(velocity.y, 0.09), velocity.z * 0.9);
        }
    }
}
