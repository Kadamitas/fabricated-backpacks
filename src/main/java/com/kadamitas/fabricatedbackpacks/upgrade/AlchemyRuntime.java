package com.kadamitas.fabricatedbackpacks.upgrade;

import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.OminousBottleAmplifier;
import net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ClearAllStatusEffectsConsumeEffect;
import net.minecraft.world.item.consume_effects.ConsumeEffect;
import net.minecraft.world.item.consume_effects.RemoveStatusEffectsConsumeEffect;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** No item leaves storage until use completes; interruption therefore cannot lose an in-flight consumable. */
public final class AlchemyRuntime {
    public enum Condition { NEVER, ALWAYS, UNDER_WATER, ON_FIRE, FALLING, MINING, SPRINTING, HURT, NEGATIVE_EFFECT }
    private record Key(MinecraftServer server, String bag, int upgrade) { }
    private record Pending(BackpackTraversal.SlotAddress source, int row, ItemStack expected, UUID target, long finish, long started, int lastSeen) { }
    private static final Map<Key, Pending> PENDING = new HashMap<>();
    private AlchemyRuntime() { }

    public static boolean supported(ItemStack stack) {
        if (stack.isEmpty() || stack.is(Items.LINGERING_POTION)) return false;
        if (!effects(stack).isEmpty()) return true;
        Consumable consumable = stack.get(DataComponents.CONSUMABLE);
        return consumable != null && consumable.onConsumeEffects().stream().anyMatch(effect -> effect instanceof RemoveStatusEffectsConsumeEffect || effect instanceof ClearAllStatusEffectsConsumeEffect);
    }

    public static Condition defaultCondition(ItemStack stack) {
        List<MobEffectInstance> effects = effects(stack);
        if (!effects.isEmpty()) {
            var type = effects.getFirst().getEffect();
            if (type.equals(MobEffects.WATER_BREATHING)) return Condition.UNDER_WATER;
            if (type.equals(MobEffects.INSTANT_HEALTH) || type.equals(MobEffects.REGENERATION)) return Condition.HURT;
            if (type.equals(MobEffects.FIRE_RESISTANCE)) return Condition.ON_FIRE;
            if (type.equals(MobEffects.SPEED)) return Condition.SPRINTING;
            if (type.equals(MobEffects.HASTE)) return Condition.MINING;
            if (type.equals(MobEffects.SLOW_FALLING)) return Condition.FALLING;
            return Condition.ALWAYS;
        }
        return supported(stack) ? Condition.NEGATIVE_EFFECT : Condition.NEVER;
    }

    public static void tick(BagInventory bag, InstalledUpgrade upgrade, ServerLevel level, BlockPos position, LivingEntity carrier) {
        Key key = new Key(level.getServer(), bag.identity(), upgrade.slot());
        Container storage = BackpackTraversal.processingInventory(bag);
        Pending pending = PENDING.get(key);
        if (pending != null) {
            LivingEntity target = level.getEntity(pending.target()) instanceof LivingEntity living ? living : null;
            int source = BackpackTraversal.resolve(storage, pending.source());
            ItemStack current = source < 0 ? ItemStack.EMPTY : storage.getItem(source);
            if (target == null || !target.isAlive() || !ItemStack.isSameItemSameComponents(current, pending.expected())
                    || current.isEmpty() || !storage.canTakeItem(null, source, current)
                    || !filterMatches(current, bag.ghost(upgrade, pending.row()), bag.settings(upgrade), upgrade.kind().advanced())
                    || (carrier == null && !new AABB(position).inflate(BackpackConfig.get().upgrades().alchemy().range()).intersects(target.getBoundingBox()))
                    || (carrier != null && carrier != target)) {
                PENDING.remove(key);
                clearProgress(bag, upgrade);
                return;
            }
            PENDING.put(key, new Pending(pending.source(), pending.row(), pending.expected(), pending.target(), pending.finish(), pending.started(), level.getServer().getTickCount()));
            Consumable consumable = current.get(DataComponents.CONSUMABLE);
            int elapsed = (int) (level.getGameTime() - pending.started());
            if (consumable != null && consumable.shouldEmitParticlesAndSounds(elapsed)) consumable.emitParticlesAndSounds(level.getRandom(), target, current, 5);
            if (level.getGameTime() >= pending.finish()) {
                PENDING.remove(key);
                if (eligible(current, target, bag.settings(upgrade))) apply(bag, storage, source, level, target, carrier);
                clearProgress(bag, upgrade);
            }
            return;
        }
        clearProgress(bag, upgrade);
        var rules = BackpackConfig.get().upgrades().alchemy();
        if (level.getGameTime() % rules.interval() != 0) return;
        CompoundTag settings = bag.settings(upgrade);
        List<LivingEntity> targets = carrier != null ? List.of(carrier)
                : level.getEntitiesOfClass(LivingEntity.class, new AABB(position).inflate(rules.range()), target -> target.isAlive() && !target.isSpectator());
        String selection = upgrade.kind().advanced() ? settings.getStringOr("alchemy_targets", "BOTH") : "BOTH";
        for (LivingEntity target : targets) {
            if (target.isSpectator() || !target.isAlive() || (selection.equals("PLAYERS") && !(target instanceof ServerPlayer))
                    || (selection.equals("NONPLAYERS") && target instanceof ServerPlayer)) continue;
            for (int row = 0; row < bag.filterSlots(upgrade); row++) {
                ItemStack ghost = bag.ghost(upgrade, row);
                if (!supported(ghost) || !condition(settings, row, ghost, target)) continue;
                for (int slot = 0; slot < storage.getContainerSize(); slot++) {
                    ItemStack item = storage.getItem(slot);
                    if (!storage.canTakeItem(null, slot, item) || !filterMatches(item, ghost, settings, upgrade.kind().advanced()) || !eligible(item, target, settings)) continue;
                    if (item.is(Items.SPLASH_POTION) || (item.is(Items.GOLDEN_APPLE) && canConvert(target))) {
                        apply(bag, storage, slot, level, target, carrier);
                    } else {
                        Consumable consumable = item.get(DataComponents.CONSUMABLE);
                        if (consumable == null) continue;
                        long end = level.getGameTime() + Math.max(1, consumable.consumeTicks());
                        PENDING.put(key, new Pending(BackpackTraversal.address(storage, slot), row, item.copyWithCount(1), target.getUUID(), end, level.getGameTime(), level.getServer().getTickCount()));
                        int active = row;
                        bag.updateSettings(upgrade, tag -> { tag.putInt("alchemy_active_row", active); tag.putLong("alchemy_finish", end); });
                    }
                    return; // A backpack can have only one consumption in flight.
                }
            }
        }
    }

    private static boolean condition(CompoundTag settings, int row, ItemStack ghost, LivingEntity target) {
        Condition condition;
        try { condition = Condition.valueOf(settings.getStringOr("alchemy_condition_" + row, defaultCondition(ghost).name())); }
        catch (IllegalArgumentException invalid) { condition = Condition.NEVER; }
        return switch (condition) {
            case NEVER -> false;
            case ALWAYS -> true;
            case UNDER_WATER -> target.isUnderWater();
            case ON_FIRE -> target.isOnFire();
            case FALLING -> target.fallDistance > 2;
            case MINING -> target instanceof ServerPlayer player && player.gameMode instanceof UpgradeAccess.Mining mining && mining.fabricatedBackpacks$isDestroyingBlock();
            case SPRINTING -> target.isSprinting();
            case HURT -> target.getHealth() < target.getMaxHealth()
                    && target.getHealth() / target.getMaxHealth() < Math.clamp(settings.getIntOr("alchemy_health_" + row, 75), 0, 100) / 100.0;
            case NEGATIVE_EFFECT -> target.getActiveEffects().stream().anyMatch(effect -> effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL);
        };
    }

    private static List<MobEffectInstance> effects(ItemStack item) {
        List<MobEffectInstance> effects = new ArrayList<>();
        PotionContents potion = item.get(DataComponents.POTION_CONTENTS);
        if (potion != null) potion.getAllEffects().forEach(effects::add);
        Consumable consumable = item.get(DataComponents.CONSUMABLE);
        if (consumable != null) for (ConsumeEffect effect : consumable.onConsumeEffects()) {
            if (effect instanceof ApplyStatusEffectsConsumeEffect apply) effects.addAll(apply.effects());
        }
        OminousBottleAmplifier omen = item.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
        if (omen != null) effects.add(new MobEffectInstance(MobEffects.BAD_OMEN, OminousBottleAmplifier.EFFECT_DURATION, omen.value()));
        return effects;
    }

    private static boolean filterMatches(ItemStack item, ItemStack ghost, CompoundTag settings, boolean advanced) {
        if (!supported(item) || !ItemStack.isSameItem(item, ghost)) return false;
        boolean duration = !advanced || settings.getBooleanOr("alchemy_match_duration", true);
        boolean amplifier = !advanced || settings.getBooleanOr("alchemy_match_amplifier", true);
        boolean all = !advanced || settings.getBooleanOr("alchemy_match_all", true);
        if (duration && amplifier && all) return ItemStack.isSameItemSameComponents(item, ghost);
        List<MobEffectInstance> expected = effects(ghost);
        List<MobEffectInstance> actual = effects(item);
        if (expected.isEmpty()) return true;
        java.util.function.Predicate<MobEffectInstance> included = expectedEffect -> actual.stream().anyMatch(effect ->
                effect.getEffect().equals(expectedEffect.getEffect()) && (!duration || effect.getDuration() == expectedEffect.getDuration())
                        && (!amplifier || effect.getAmplifier() == expectedEffect.getAmplifier()));
        return all ? expected.stream().allMatch(included) : expected.stream().anyMatch(included);
    }

    private static boolean canConvert(LivingEntity target) {
        return target instanceof ZombieVillager villager && !villager.isConverting() && villager.hasEffect(MobEffects.WEAKNESS)
                && target instanceof UpgradeAccess.VillagerConversion;
    }

    private static boolean eligible(ItemStack item, LivingEntity target, CompoundTag settings) {
        if (item.is(Items.GOLDEN_APPLE) && target instanceof ZombieVillager villager && villager.isConverting()) return false;
        if (item.is(Items.GOLDEN_APPLE) && canConvert(target)) return true;
        List<MobEffectInstance> offered = effects(item);
        java.util.function.Predicate<MobEffectInstance> missing = effect -> {
            if (effect.getEffect().value().isInstantaneous()) return !effect.getEffect().equals(MobEffects.INSTANT_HEALTH) || target.getHealth() < target.getMaxHealth();
            MobEffectInstance active = target.getEffect(effect.getEffect());
            return active == null || active.getAmplifier() < effect.getAmplifier();
        };
        boolean beneficial = !offered.isEmpty() && (settings.getBooleanOr("alchemy_all_missing", true)
                ? offered.stream().allMatch(missing) : offered.stream().anyMatch(missing));
        if (beneficial) return true;
        Consumable consumable = item.get(DataComponents.CONSUMABLE);
        if (consumable != null) for (ConsumeEffect effect : consumable.onConsumeEffects()) {
            if (effect instanceof ClearAllStatusEffectsConsumeEffect && !target.getActiveEffects().isEmpty()) return true;
            if (effect instanceof RemoveStatusEffectsConsumeEffect remove
                    && target.getActiveEffects().stream().anyMatch(active -> remove.effects().contains(active.getEffect()))) return true;
        }
        return false;
    }

    private static void apply(BagInventory bag, Container storage, int source, ServerLevel level, LivingEntity target, LivingEntity carrier) {
        ItemStack stack = storage.getItem(source);
        if (!storage.canTakeItem(null, source, stack)) return;
        if (stack.is(Items.GOLDEN_APPLE) && canConvert(target)) {
            storage.setItem(source, stack.copyWithCount(stack.getCount() - 1));
            ((UpgradeAccess.VillagerConversion) target).fabricatedBackpacks$startConverting(
                    carrier instanceof ServerPlayer ? carrier.getUUID() : null, level.getRandom().nextInt(2401) + 3600);
        } else if (stack.is(Items.SPLASH_POTION)) {
            ItemStack dose = stack.copyWithCount(1);
            storage.setItem(source, stack.copyWithCount(stack.getCount() - 1));
            ThrownSplashPotion splash = new ThrownSplashPotion(level, target.getX(), target.getY(), target.getZ(), dose);
            if (carrier != null) splash.setOwner(carrier);
            splash.onHitAsPotion(level, dose, new EntityHitResult(target));
            level.levelEvent(2002, target.blockPosition(), dose.getOrDefault(DataComponents.POTION_CONTENTS, PotionContents.EMPTY).getColor());
            splash.discard();
        } else ConsumptionRuntime.consumeOne(bag, storage, source, level, target);
    }

    private static void clearProgress(BagInventory bag, InstalledUpgrade upgrade) {
        CompoundTag state = bag.settings(upgrade);
        if (state.getIntOr("alchemy_active_row", -1) != -1 || state.getLongOr("alchemy_finish", 0) != 0) {
            bag.updateSettings(upgrade, tag -> { tag.putInt("alchemy_active_row", -1); tag.putLong("alchemy_finish", 0); });
        }
    }

    public static void cancel(BagInventory bag, int slot, MinecraftServer server) {
        PENDING.remove(new Key(server, bag.identity(), slot));
        bag.installedUpgrades().stream().filter(upgrade -> upgrade.slot() == slot && upgrade.kind().family().equals("alchemy"))
                .findFirst().ifPresent(upgrade -> clearProgress(bag, upgrade));
    }
    public static void endServerTick(MinecraftServer server) {
        PENDING.entrySet().removeIf(entry -> entry.getKey().server() == server && server.getTickCount() - entry.getValue().lastSeen() > 10);
    }
    public static void stopAll(MinecraftServer server) { PENDING.keySet().removeIf(key -> key.server() == server); }
}
