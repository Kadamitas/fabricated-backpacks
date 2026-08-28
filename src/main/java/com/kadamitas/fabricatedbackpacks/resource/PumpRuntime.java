package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;

import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.phys.AABB;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

final class PumpRuntime {
    private static final GameProfile PROFILE = new GameProfile(
            UUID.nameUUIDFromBytes("fabricated_backpacks:pump".getBytes(StandardCharsets.UTF_8)), "[Backpack]");

    private PumpRuntime() {}

    static void tick(BagInventory bag, InstalledUpgrade upgrade, ServerLevel level, BlockPos position, LivingEntity carrier) {
        CompoundTag settings = bag.settings(upgrade);
        long now = level.getGameTime();
        var rules = BackpackConfig.get().upgrades().pump();
        long next = NbtAccess.getLongOr(settings, "next_work", 0);
        if (!NbtAccess.getBooleanOr(settings, "enabled", true) || now < next && next - now <= Math.max(rules.idleTicks(),
                rules.handlerTicks() + rules.worldRange() * rules.worldRange())) return;
        boolean advanced = upgrade.kind() == UpgradeKind.ADVANCED_PUMP;
        boolean output = NbtAccess.getStringOr(settings, "direction", "input").equals("output");
        Predicate<FluidVariant> filter = filter(bag, upgrade);
        long fastUntil = NbtAccess.getLongOr(settings, "fast_until", 0);
        int cooldown = fastUntil > now && fastUntil - now <= rules.handGraceTicks() ? rules.handTicks() : rules.idleTicks();
        if (advanced && NbtAccess.getBooleanOr(settings, "hands", true) && hands(bag, level, position, carrier, output, filter)) {
            bag.updateSettings(upgrade, tag -> tag.putLong("fast_until", now + rules.handGraceTicks()));
            cooldown = rules.handTicks();
        } else if (NbtAccess.getBooleanOr(settings, "handlers", true) && handlers(bag, level, position, output, filter)) {
            cooldown = rules.handlerTicks();
        } else if (advanced && NbtAccess.getBooleanOr(settings, "world", false)) {
            ServerPlayer actor = carrier instanceof ServerPlayer player ? player : FakePlayer.get(level, PROFILE);
            if (actor instanceof FakePlayer) actor.setPos(net.minecraft.world.phys.Vec3.atCenterOf(position));
            int distance = output ? worldOutput(bag, level, position, actor, filter)
                    : worldInput(bag, level, position, actor, filter);
            if (distance >= 0) cooldown = rules.handlerTicks() + distance;
        }
        int delay = cooldown;
        bag.updateSettings(upgrade, tag -> tag.putLong("next_work", now + delay));
    }

    static void action(BagInventory bag, InstalledUpgrade upgrade, String action) {
        bag.updateSettings(upgrade, tag -> {
            if (action.equals("direction")) {
                tag.putString("direction", NbtAccess.getStringOr(tag, "direction", "input").equals("input") ? "output" : "input");
            } else if (action.equals("handlers")) {
                tag.putBoolean("handlers", !NbtAccess.getBooleanOr(tag, "handlers", true));
            } else if (upgrade.kind() == UpgradeKind.ADVANCED_PUMP && action.equals("hands")) {
                tag.putBoolean("hands", !NbtAccess.getBooleanOr(tag, "hands", true));
            } else if (upgrade.kind() == UpgradeKind.ADVANCED_PUMP && action.equals("world")) {
                tag.putBoolean("world", !NbtAccess.getBooleanOr(tag, "world", false));
            }
        });
    }

    private static Predicate<FluidVariant> filter(BagInventory bag, InstalledUpgrade upgrade) {
        if (upgrade.kind() != UpgradeKind.ADVANCED_PUMP || bag.filterItems(upgrade).isEmpty()) return fluid -> true;
        List<FluidVariant> selected = bag.filterItems(upgrade).stream().map(item ->
                StorageUtil.findStoredResource(ContainerItemContext.withConstant(item).find(FluidStorage.ITEM)))
                .filter(java.util.Objects::nonNull).toList();
        return selected::contains;
    }

    private static boolean handlers(BagInventory bag, ServerLevel level, BlockPos position,
                                    boolean output, Predicate<FluidVariant> filter) {
        Storage<FluidVariant> internal = ResourceRuntime.fluidStorage(bag);
        for (Direction side : Direction.values()) {
            BlockPos target = position.relative(side);
            if (!ResourceRuntime.connectionAllowed(level, position, side)) continue;
            Storage<FluidVariant> external = FluidStorage.SIDED.find(level, target, side.getOpposite());
            if (external == null) continue;
            try (Transaction transaction = Transaction.openOuter()) {
                long moved = StorageUtil.move(output ? internal : external, output ? external : internal,
                        filter, FluidConstants.BUCKET, transaction);
                if (moved > 0) {
                    transaction.commit();
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hands(BagInventory bag, ServerLevel level, BlockPos position, LivingEntity carrier,
                                 boolean output, Predicate<FluidVariant> filter) {
        List<? extends Player> players = carrier instanceof Player player ? List.of(player)
                : level.getEntitiesOfClass(ServerPlayer.class, new AABB(position).inflate(BackpackConfig.get().upgrades().pump().playerRange()),
                        player -> player.isAlive() && !player.isSpectator());
        for (Player player : players) {
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack held = player.getItemInHand(hand);
                if (held.getCount() != 1 || sameBag(bag, held)) continue;
                Storage<FluidVariant> external = ContainerItemContext.ofPlayerHand(player, hand).find(FluidStorage.ITEM);
                if (external == null) continue;
                Storage<FluidVariant> internal = ResourceRuntime.fluidStorage(bag);
                try (Transaction transaction = Transaction.openOuter()) {
                    long moved = StorageUtil.move(output ? internal : external, output ? external : internal,
                            filter, FluidConstants.BUCKET, transaction);
                    if (moved > 0) {
                        transaction.commit();
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int worldInput(BagInventory bag, ServerLevel level, BlockPos origin,
                                  ServerPlayer actor, Predicate<FluidVariant> filter) {
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        for (Direction side : Direction.values()) pending.add(origin.relative(side));
        Set<BlockPos> visited = new HashSet<>();
        int range = BackpackConfig.get().upgrades().pump().worldRange();
        while (!pending.isEmpty()) {
            BlockPos target = pending.removeFirst();
            if (origin.distSqr(target) >= range * range || !visited.add(target) || !level.hasChunkAt(target)) continue;
            BlockState state = level.getBlockState(target);
            var fluidState = state.getFluidState();
            if (fluidState.isEmpty()) continue;
            FluidVariant fluid = FluidVariant.of(fluidState.getType());
            if (!filter.test(fluid)) continue;
            if (state.getBlock() instanceof LiquidBlock && fluidState.isSource() && permitted(bag, level, actor, target)) {
                Storage<FluidVariant> tanks = ResourceRuntime.fluids(bag, false);
                try (Transaction transaction = Transaction.openOuter()) {
                    if (tanks.insert(fluid, FluidConstants.BUCKET, transaction) == FluidConstants.BUCKET
                            && new WorldFluidChange(level, target).set(Blocks.AIR.defaultBlockState(), transaction)) {
                        transaction.commit();
                        return (int) origin.distSqr(target);
                    }
                }
            }
            for (Direction side : Direction.values()) {
                BlockPos next = target.relative(side);
                if (level.hasChunkAt(next) && fluidState.getType().isSame(level.getFluidState(next).getType())) pending.add(next);
            }
        }
        return -1;
    }

    private static int worldOutput(BagInventory bag, ServerLevel level, BlockPos origin,
                                   ServerPlayer actor, Predicate<FluidVariant> filter) {
        Storage<FluidVariant> tanks = ResourceRuntime.fluids(bag, false);
        FluidVariant fluid = StorageUtil.findStoredResource(tanks,
                candidate -> candidate.getFluid() instanceof FlowingFluid && filter.test(candidate)
                        && candidate.getComponents().isEmpty());
        if (fluid == null) return -1;
        for (Direction side : Direction.values()) {
            if (side == Direction.UP) continue;
            BlockPos target = origin.relative(side);
            if (!level.hasChunkAt(target) || !permitted(bag, level, actor, target)) continue;
            BlockState state = level.getBlockState(target);
            if (!state.isAir() && (!(state.getBlock() instanceof LiquidBlock) || state.getFluidState().isSource())) continue;
            boolean evaporates = level.dimensionType().ultraWarm()
                    && fluid.getFluid().is(FluidTags.WATER);
            try (Transaction transaction = Transaction.openOuter()) {
                if (tanks.extract(fluid, FluidConstants.BUCKET, transaction) != FluidConstants.BUCKET) continue;
                if (!evaporates && !new WorldFluidChange(level, target)
                        .set(fluid.getFluid().defaultFluidState().createLegacyBlock(), transaction)) continue;
                transaction.commit();
            }
            if (evaporates) {
                level.playSound(null, target, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F, 2.6F);
                level.sendParticles(ParticleTypes.LARGE_SMOKE, target.getX() + 0.5, target.getY() + 0.5,
                        target.getZ() + 0.5, 8, 0.25, 0.25, 0.25, 0);
            }
            return 1;
        }
        return -1;
    }

    private static boolean permitted(BagInventory bag, ServerLevel level, ServerPlayer actor, BlockPos target) {
        return actor.isAlive() && !actor.isSpectator() && level.mayInteract(actor, target)
                && actor.mayUseItemAt(target, Direction.UP, bag.stack())
                && PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(
                        level, actor, target, level.getBlockState(target), level.getBlockEntity(target));
    }

    static boolean sameBag(BagInventory bag, ItemStack item) {
        if (!BackpackRegistry.isBackpack(item)) return false;
        String identity = item.getOrDefault(BagComponents.IDENTITY, "");
        if (bag.stack() == item || !identity.isEmpty() && BackpackTraversal.inventoryBags(bag).stream()
                .anyMatch(node -> identity.equals(node.inventory().identity()))) return true;
        // A child pump must also reject a context containing its outer bag: exchanging
        // that context while mutating the child would create two copies of one resource.
        return item.getOrDefault(BagComponents.CONTENTS, InventorySnapshot.EMPTY).entries().stream()
                .map(entry -> entry.create()).filter(BackpackRegistry::isBackpack)
                .anyMatch(child -> bag.identity().equals(child.getOrDefault(BagComponents.IDENTITY, "")));
    }
}
