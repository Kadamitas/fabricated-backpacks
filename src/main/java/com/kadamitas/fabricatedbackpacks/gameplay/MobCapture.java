package com.kadamitas.fabricatedbackpacks.gameplay;

import com.kadamitas.fabricatedbackpacks.domain.CaptureLayout;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.config.RuleMatchers;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import java.util.HashSet;
import java.util.Set;

/** Captures retain the original entity data and claim real rectangular storage cells. */
public final class MobCapture {
    private MobCapture() {}
    public static boolean capture(BagInventory bag, LivingEntity entity, ServerPlayer player) {
        InstalledUpgrade catcher = bag.installedUpgrades().stream().filter(upgrade -> upgrade.kind().family().equals("mob_catcher")
                && bag.settings(upgrade).getBooleanOr("enabled", true)).findFirst().orElse(null);
        if (catcher == null || !(entity instanceof Mob) || !entity.isAlive() || player.isSpectator()
                || entity.isPassenger() || entity.isVehicle() || entity.level() != player.level()
                || entity.distanceToSqr(player) > player.entityInteractionRange() * player.entityInteractionRange()) return false;
        if (entity.getType() == net.minecraft.world.entity.EntityTypes.WITHER || entity.getType() == net.minecraft.world.entity.EntityTypes.ENDER_DRAGON) return false;
        if (entity instanceof OwnableEntity ownable && ownable.getOwnerReference() != null
                && !ownable.getOwnerReference().getUUID().equals(player.getUUID())) return false;
        if (!player.level().mayInteract(player, entity.blockPosition())) return false;
        var rules = BackpackConfig.get().capture();
        if (RuleMatchers.entity(entity, rules.blockedEntities()) || entity.typeHolder().is(net.minecraft.tags.TagKey.create(
                net.minecraft.core.registries.Registries.ENTITY_TYPE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("fabricated_backpacks", "unsupported_capture")))) return false;
        if (rules.excludeInventories() && (entity instanceof net.minecraft.world.Container
                || entity instanceof net.minecraft.world.entity.npc.InventoryCarrier
                || entity instanceof net.minecraft.world.entity.HasCustomInventoryScreen)) return false;
        boolean hostile = !RuleMatchers.entity(entity, rules.passiveEntities())
                && (RuleMatchers.entity(entity, rules.hostileEntities()) || entity instanceof net.minecraft.world.entity.monster.Enemy
                || entity.getType().getCategory() == MobCategory.MONSTER);
        int cost = CaptureLayout.captureCost(entity.getHealth(), entity.getMaxHealth(), hostile);
        if (!CaptureLayout.withinUpgradeLimit(cost, hostile, catcher.kind().advanced(),
                catcher.kind().advanced() ? rules.hostileLimit() : rules.passiveLimit())) return false;
        Set<Integer> occupied = new HashSet<>();
        for (int slot = 0; slot < bag.getContainerSize(); slot++) if (!bag.getItem(slot).isEmpty() || bag.blocked(slot)) occupied.add(slot);
        if (bag.getContainerSize() > 144) return false; // Preserve oversized legacy data without corrupting its unsupported capture geometry.
        var layout = new CaptureLayout(bag.columns(), bag.getContainerSize(), occupied);
        var rectangle = layout.allocate(cost, entity.getBbWidth(), entity.getBbHeight()).orElse(null);
        if (rectangle == null) return false;
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        if (!entity.save(output)) return false;
        CompoundTag data = output.buildResult();
        if (data.sizeInBytes() > 128 * 1024) return false;
        CompoundTag capture = new CompoundTag();
        capture.put("entity", data);
        capture.putString("name", entity.getName().getString());
        capture.putIntArray("slots", layout.cells(rectangle).stream().sorted().mapToInt(Integer::intValue).toArray());
        capture.putInt("x", rectangle.x()); capture.putInt("y", rectangle.y());
        capture.putInt("width", rectangle.width()); capture.putInt("height", rectangle.height());
        bag.updateSettings(tag -> {
            ListTag captures = tag.getListOrEmpty("captured_entities");
            captures.add(capture);
            tag.put("captured_entities", captures);
            updateOccupied(tag, captures);
        });
        entity.discard();
        return true;
    }
    public static boolean release(BagInventory bag, int index, ServerPlayer player, Vec3 target) {
        ListTag captures = bag.settings().getListOrEmpty("captured_entities");
        if (index < 0 || index >= captures.size() || player.isSpectator() || player.distanceToSqr(target) > 64) return false;
        var pos = net.minecraft.core.BlockPos.containing(target);
        if (!player.level().mayInteract(player, pos) || !player.level().getWorldBorder().isWithinBounds(pos)) return false;
        CompoundTag capture = captures.getCompoundOrEmpty(index);
        var entity = EntityType.loadEntityRecursive(capture.getCompoundOrEmpty("entity"), player.level(),
                new EntitySpawnRequest(EntitySpawnReason.LOAD, false), EntityProcessor.NOP);
        if (!(entity instanceof LivingEntity) || player.level().getEntityInAnyDimension(entity.getUUID()) != null) return false;
        entity.snapTo(target, player.getYRot(), 0);
        if (!player.level().noCollision(entity) || !player.level().tryAddFreshEntityWithPassengers(entity)) return false;
        captures.remove(index);
        bag.updateSettings(tag -> {
            if (captures.isEmpty()) tag.remove("captured_entities"); else tag.put("captured_entities", captures);
            updateOccupied(tag, captures);
        });
        return true;
    }
    private static void updateOccupied(CompoundTag tag, ListTag captures) {
        Set<Integer> occupied = new HashSet<>();
        for (int index = 0; index < captures.size(); index++) {
            for (int slot : captures.getCompoundOrEmpty(index).getIntArray("slots").orElseGet(() -> new int[0])) occupied.add(slot);
        }
        tag.putIntArray("captured_slots", occupied.stream().sorted().mapToInt(Integer::intValue).toArray());
    }
}
