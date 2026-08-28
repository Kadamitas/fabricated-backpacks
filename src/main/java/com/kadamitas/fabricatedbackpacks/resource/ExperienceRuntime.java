package com.kadamitas.fabricatedbackpacks.resource;

import com.kadamitas.fabricatedbackpacks.domain.ExperienceMath;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.mixin.ExperienceOrbAccessor;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;
import java.util.ArrayList;
import java.util.List;

final class ExperienceRuntime {
    private ExperienceRuntime() {}

    static void tick(BagInventory bag, InstalledUpgrade upgrade, ServerLevel level, BlockPos position, LivingEntity carrier) {
        CompoundTag settings = bag.settings(upgrade);
        if (!settings.getBooleanOr("enabled", true)) return;
        var rules = BackpackConfig.get().upgrades().experience();
        long target = ExperienceMath.pointsAtLevel(Math.clamp(settings.getIntOr("target", 10), 0, 10_000));
        long maximum = Math.clamp(settings.getIntOr("transfer_points", rules.transferPoints()), 1, rules.transferPoints());
        String direction = settings.getStringOr("direction", "input");
        List<ServerPlayer> players = carrier instanceof ServerPlayer player ? List.of(player)
                : carrier == null ? level.getEntitiesOfClass(ServerPlayer.class, new AABB(position).inflate(rules.range()),
                        player -> player.isAlive() && !player.isSpectator()) : List.of();
        for (ServerPlayer player : players) {
            if (!player.isAlive() || player.isSpectator()) continue;
            long points = new PlayerExperience(player).points();
            if ((direction.equals("input") || direction.equals("keep")) && points > target) {
                transfer(bag, player, Math.min(maximum, points - target), true);
            } else if ((direction.equals("output") || direction.equals("keep")) && points < target) {
                transfer(bag, player, Math.min(maximum, target - points), false);
            }
            if (rules.allowMending() && settings.getBooleanOr("mending", true)) {
                mend(bag, player, Math.clamp(settings.getIntOr("mending_points", rules.mendingPoints()), 1, rules.mendingPoints()));
            }
        }
    }

    static void action(BagInventory bag, InstalledUpgrade upgrade, String action, ServerPlayer player) {
        CompoundTag settings = bag.settings(upgrade);
        int levels = Math.clamp(settings.getIntOr("levels", 1), 1, 10_000);
        long points = new PlayerExperience(player).points();
        int level = ExperienceMath.levelAtPoints(points);
        switch (action) {
            case "store" -> transfer(bag, player, points - ExperienceMath.pointsAtLevel(Math.max(0, level - levels)), true);
            case "take" -> transfer(bag, player,
                    Math.max(0, ExperienceMath.pointsAtLevel((int) Math.min(Integer.MAX_VALUE, (long) level + levels)) - points), false);
            case "store_all" -> transfer(bag, player, points, true);
            case "take_all" -> transfer(bag, player, Math.max(0, ExperienceMath.pointsAtLevel(10_000) - points), false);
            case "target_up" -> bag.updateSettings(upgrade, tag -> tag.putInt("target", Math.clamp((long) tag.getIntOr("target", 10) + 1, 0, 10_000)));
            case "target_down" -> bag.updateSettings(upgrade, tag -> tag.putInt("target", Math.clamp((long) tag.getIntOr("target", 10) - 1, 0, 10_000)));
            case "levels_up" -> bag.updateSettings(upgrade, tag -> tag.putInt("levels", Math.clamp((long) tag.getIntOr("levels", 1) + 1, 1, 10_000)));
            case "levels_down" -> bag.updateSettings(upgrade, tag -> tag.putInt("levels", Math.clamp((long) tag.getIntOr("levels", 1) - 1, 1, 10_000)));
            case "mending" -> bag.updateSettings(upgrade, tag -> tag.putBoolean("mending", !tag.getBooleanOr("mending", true)));
            case "direction" -> bag.updateSettings(upgrade, tag -> tag.putString("direction",
                    switch (tag.getStringOr("direction", "input")) {
                        case "input" -> "output";
                        case "output" -> "keep";
                        case "keep" -> "off";
                        default -> "input";
                    }));
            default -> { }
        }
    }

    private static long transfer(BagInventory bag, ServerPlayer player, long requested, boolean intoTank) {
        PlayerExperience ledger = new PlayerExperience(player);
        long before = ledger.points();
        long limit = Math.min(Math.max(0, requested), intoTank ? before : Long.MAX_VALUE - before);
        try (Transaction transaction = Transaction.openOuter()) {
            long moved = intoTank ? ResourceRuntime.insertExperience(bag, limit, transaction)
                    : ResourceRuntime.extractExperience(bag, limit, transaction);
            if (moved > 0 && ledger.setPoints(intoTank ? before - moved : before + moved, transaction)) {
                transaction.commit();
                return moved;
            }
        }
        return 0;
    }

    static void collect(BagInventory bag, ServerLevel level, BlockPos position, LivingEntity carrier) {
        long now = level.getGameTime();
        for (InstalledUpgrade upgrade : bag.installedUpgrades()) {
            CompoundTag settings = bag.settings(upgrade);
            if (!settings.getBooleanOr("enabled", true)) continue;
            if (upgrade.kind().family().equals("cooking") && upgrade.kind().id().startsWith("auto_")) {
                if (now % BackpackConfig.get().upgrades().cooking().retryMinimum() != 0) continue;
                double stored = settings.getDoubleOr("experience", 0);
                if (!Double.isFinite(stored) || stored < 1) continue;
                try (Transaction transaction = Transaction.openOuter()) {
                    long accepted = ResourceRuntime.insertExperience(bag, (long) Math.floor(stored), transaction);
                    if (accepted > 0) {
                        new CookingExperience(bag, upgrade).subtract(accepted, transaction);
                        transaction.commit();
                    }
                }
            } else if (upgrade.kind().family().equals("magnet") && settings.getBooleanOr("magnet_xp", true)) {
                var rules = BackpackConfig.get().upgrades().magnet();
                long next = settings.getLongOr("magnet_xp_next", 0);
                if (next > now && next - now <= Math.max(rules.activeTicks(), rules.idleTicks())) continue;
                int range = rules.radius(upgrade.kind());
                AABB area = carrier == null ? new AABB(position).inflate(range) : carrier.getBoundingBox().inflate(range);
                boolean moved = false;
                for (ExperienceOrb orb : level.getEntitiesOfClass(ExperienceOrb.class, area, entity -> !entity.isRemoved())) {
                    OrbExperience ledger = new OrbExperience(level, orb);
                    long available = ledger.points();
                    if (available <= 0) continue;
                    try (Transaction transaction = Transaction.openOuter()) {
                        long accepted = ResourceRuntime.insertExperience(bag, available, transaction);
                        if (accepted > 0 && ledger.take(accepted, transaction)) { transaction.commit(); moved = true; }
                    }
                }
                int delay = moved ? rules.activeTicks() : rules.idleTicks();
                bag.updateSettings(upgrade, tag -> tag.putLong("magnet_xp_next", now + delay));
            }
        }
    }

    private static void mend(BagInventory bag, ServerPlayer player, int pointBudget) {
        var chosen = EnchantmentHelper.getRandomItemWith(
                EnchantmentEffectComponents.REPAIR_WITH_XP, player, ItemStack::isDamaged);
        if (chosen.isEmpty()) return;
        ItemStack item = chosen.get().itemStack();
        Storage<FluidVariant> tanks = ResourceRuntime.fluids(bag, false);
        FluidVariant experience = ResourceComponents.experience();
        try (Transaction transaction = Transaction.openOuter()) {
            long available = StorageUtil.simulateExtract(tanks, experience,
                    pointBudget * FluidAmount.DROPLETS_PER_XP, transaction);
            if (available == 0) return;
            int budget = (int) Math.ceilDiv(available, FluidAmount.DROPLETS_PER_XP);
            int possible = EnchantmentHelper.modifyDurabilityToRepairFromXp(player.level(), item, budget);
            if (possible <= 0) return;
            long budgetDroplets = budget * FluidAmount.DROPLETS_PER_XP;
            int repaired = (int) Math.min(item.getDamageValue(), possible * available / budgetDroplets);
            if (repaired <= 0) return;
            long cost = Math.ceilDiv(repaired * budgetDroplets, possible);
            if (tanks.extract(experience, cost, transaction) != cost) return;
            new ItemDamage(item).repair(repaired, transaction);
            transaction.commit();
        }
        player.getInventory().setChanged();
    }

    private static final class ItemDamage extends SnapshotParticipant<Integer> {
        private final ItemStack item;
        ItemDamage(ItemStack item) { this.item = item; }
        void repair(int amount, TransactionContext transaction) {
            if (amount < 0 || amount > item.getDamageValue()) throw new IllegalArgumentException("Invalid repair amount");
            updateSnapshots(transaction);
            item.setDamageValue(item.getDamageValue() - amount);
        }
        @Override protected Integer createSnapshot() { return item.getDamageValue(); }
        @Override protected void readSnapshot(Integer damage) { item.setDamageValue(damage); }
    }

    private static final class PlayerExperience extends SnapshotParticipant<PlayerState> {
        private final ServerPlayer player;
        PlayerExperience(ServerPlayer player) { this.player = player; }

        long points() {
            int level = Math.max(0, player.experienceLevel);
            long base = ExperienceMath.pointsAtLevel(level);
            long cost = ExperienceMath.pointsToNextLevel(level);
            long partial = Math.clamp(Math.round((double) player.experienceProgress * cost), 0, cost - 1);
            return partial > Long.MAX_VALUE - base ? Long.MAX_VALUE : base + partial;
        }

        boolean setPoints(long points, TransactionContext transaction) {
            ExperienceMath.LevelProgress split = ExperienceMath.splitPoints(points);
            if (split.pointsIntoLevel() > Integer.MAX_VALUE
                    || ExperienceMath.pointsToNextLevel(split.level()) > Integer.MAX_VALUE) return false;
            updateSnapshots(transaction);
            player.setExperienceLevels(split.level());
            player.setExperiencePoints((int) split.pointsIntoLevel());
            player.totalExperience = (int) Math.min(Integer.MAX_VALUE, points);
            // Extremely high vanilla float bars can no longer represent each individual point.
            return points() == points;
        }

        @Override protected PlayerState createSnapshot() {
            return new PlayerState(player.experienceLevel, player.experienceProgress, player.totalExperience);
        }
        @Override protected void readSnapshot(PlayerState state) {
            player.setExperienceLevels(state.level);
            player.setExperiencePoints(0);
            player.experienceProgress = state.progress;
            player.totalExperience = state.total;
        }
    }

    private record PlayerState(int level, float progress, int total) {}

    private static final class OrbExperience extends SnapshotParticipant<OrbState> {
        private final ServerLevel level;
        private final ExperienceOrb orb;
        private final ExperienceOrbAccessor access;
        private final List<ExperienceOrb> created = new ArrayList<>();

        OrbExperience(ServerLevel level, ExperienceOrb orb) {
            this.level = level;
            this.orb = orb;
            this.access = (ExperienceOrbAccessor) orb;
        }

        long points() {
            return orb.isRemoved() ? 0 : (long) Math.max(0, orb.getValue()) * Math.max(0, access.fabricatedBackpacks$getCount());
        }

        boolean take(long points, TransactionContext transaction) {
            if (points <= 0 || points > points()) return false;
            int value = orb.getValue();
            int count = access.fabricatedBackpacks$getCount();
            int whole = (int) (points / value);
            int partial = (int) (points % value);
            int remaining = count - whole;
            updateSnapshots(transaction);
            if (partial == 0) {
                access.fabricatedBackpacks$setCount(remaining);
            } else if (remaining == 1) {
                access.fabricatedBackpacks$setCount(1);
                access.fabricatedBackpacks$setValue(value - partial);
            } else {
                ExperienceOrb remainder = new ExperienceOrb(level, orb.position(), net.minecraft.world.phys.Vec3.ZERO, value - partial);
                if (!level.addFreshEntity(remainder)) return false;
                created.add(remainder);
                access.fabricatedBackpacks$setCount(remaining - 1);
            }
            return true;
        }

        @Override protected OrbState createSnapshot() {
            return new OrbState(access.fabricatedBackpacks$getCount(), orb.getValue(), created.size());
        }

        @Override protected void readSnapshot(OrbState state) {
            access.fabricatedBackpacks$setCount(state.count);
            access.fabricatedBackpacks$setValue(state.value);
            while (created.size() > state.createdCount) created.removeLast().discard();
        }

        @Override protected void onFinalCommit() {
            if (access.fabricatedBackpacks$getCount() == 0) orb.discard();
            created.clear();
        }
    }

    private record OrbState(int count, int value, int createdCount) {}

    private static final class CookingExperience extends SnapshotParticipant<Double> {
        private final BagInventory bag;
        private final InstalledUpgrade upgrade;
        CookingExperience(BagInventory bag, InstalledUpgrade upgrade) { this.bag = bag; this.upgrade = upgrade; }
        private double value() { return bag.settings(upgrade).getDoubleOr("experience", 0); }
        void subtract(long points, TransactionContext transaction) {
            if (points < 0 || points > value()) throw new IllegalArgumentException("Invalid cooking XP debit");
            updateSnapshots(transaction);
            bag.updateSettings(upgrade, tag -> tag.putDouble("experience", value() - points));
        }
        @Override protected Double createSnapshot() { return value(); }
        @Override protected void readSnapshot(Double value) {
            bag.updateSettings(upgrade, tag -> tag.putDouble("experience", value));
        }
    }
}
