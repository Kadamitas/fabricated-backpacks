package com.kadamitas.fabricatedbackpacks.upgrade;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;

public final class ConsumptionRuntime {
    private ConsumptionRuntime() { }

    public static List<ServerPlayer> players(ServerLevel level, BlockPos position, LivingEntity carrier) {
        return players(level, position, carrier, BackpackConfig.get().upgrades().feeding().range());
    }
    public static List<ServerPlayer> players(ServerLevel level, BlockPos position, LivingEntity carrier, int range) {
        if (carrier instanceof ServerPlayer player) return List.of(player);
        return level.getEntitiesOfClass(ServerPlayer.class, new AABB(position).inflate(range), player -> player.isAlive() && !player.isSpectator());
    }

    public static void feed(BagInventory bag, InstalledUpgrade upgrade, ServerLevel level, BlockPos position, LivingEntity carrier) {
        long next = NbtAccess.getLongOr(bag.settings(upgrade), "feeding_next", 0);
        if (level.getGameTime() < next) return;
        boolean stillHungry = false;
        Container storage = BackpackTraversal.processingInventory(bag);
        for (ServerPlayer player : players(level, position, carrier)) {
            int missing = 20 - player.getFoodData().getFoodLevel();
            if (missing <= 0 || player.isSpectator() || !player.isAlive()) continue;
            for (int slot = 0; slot < storage.getContainerSize(); slot++) {
                ItemStack stack = storage.getItem(slot);
                FoodProperties food = stack.get(DataComponents.FOOD);
                if (stack.isEmpty() || food == null || food.nutrition() <= 0
                        || stack.is(Items.OMINOUS_BOTTLE) || !storage.canTakeItem(null, slot, stack) || !UpgradeFilters.matches(bag, upgrade, stack)) continue;
                String mode = upgrade.kind().advanced() ? NbtAccess.getStringOr(bag.settings(upgrade), "hunger_mode", "HALF") : "HALF";
                boolean hurtOverride = NbtAccess.getBooleanOr(bag.settings(upgrade), "feed_when_hurt", true) && player.getHealth() < player.getMaxHealth();
                int threshold = mode.equals("FULL") ? food.nutrition() : mode.equals("ANY") ? 1 : food.nutrition() / 2;
                if (!hurtOverride && missing < threshold) continue;
                consumeOne(bag, storage, slot, level, player);
                stillHungry |= player.getFoodData().getFoodLevel() < 20;
                break;
            }
        }
        var rules = BackpackConfig.get().upgrades().feeding();
        int cooldown = stillHungry ? rules.hungryTicks() : rules.idleTicks();
        bag.updateSettings(upgrade, tag -> tag.putLong("feeding_next", level.getGameTime() + cooldown));
    }

    /** The held item is never replaced; vanilla's consume pipeline handles nutrition, effects and use remainders. */
    public static void consumeOne(BagInventory bag, int sourceSlot, ServerLevel level, LivingEntity target) {
        consumeOne(bag, BackpackTraversal.processingInventory(bag), sourceSlot, level, target);
    }

    static void consumeOne(BagInventory bag, Container storage, int sourceSlot, ServerLevel level, LivingEntity target) {
        ItemStack source = storage.getItem(sourceSlot);
        if (source.isEmpty() || !storage.canTakeItem(null, sourceSlot, source)) return;
        ItemStack consumed = source.copyWithCount(1);
        // Native 1.21.1 milk returns its container only for players. Retain the
        // declared, stack-aware remainder for automation dosing a nonplayer too.
        ItemStack nonPlayerMilkContainer = !(target instanceof Player)
                && consumed.getItem() instanceof net.minecraft.world.item.MilkBucketItem
                ? consumed.getRecipeRemainder() : ItemStack.EMPTY;
        storage.setItem(sourceSlot, source.copyWithCount(source.getCount() - 1));
        ItemStack remainder = consumed.finishUsingItem(level, target);
        if (remainder.isEmpty()) remainder = nonPlayerMilkContainer;
        returnRemainder(bag, level, target, remainder);
    }

    public static void returnRemainder(BagInventory bag, ServerLevel level, LivingEntity target, ItemStack remainder) {
        if (remainder.isEmpty()) return;
        ItemStack unaccepted = BackpackTraversal.insert(bag, remainder, false, target instanceof Player player ? player : null);
        if (target instanceof ServerPlayer player && !unaccepted.isEmpty()) player.getInventory().add(unaccepted);
        if (!unaccepted.isEmpty()) {
            ItemEntity dropped = new ItemEntity(level, target.getX(), target.getY() + .25, target.getZ(), unaccepted);
            dropped.setDefaultPickUpDelay();
            if (target instanceof ServerPlayer player) dropped.setTarget(player.getUUID());
            level.addFreshEntity(dropped);
        }
    }
}
