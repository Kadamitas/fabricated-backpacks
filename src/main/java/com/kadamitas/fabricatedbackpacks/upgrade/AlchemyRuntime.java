package com.kadamitas.fabricatedbackpacks.upgrade;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
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
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
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
        return stack.getItem() instanceof net.minecraft.world.item.MilkBucketItem
                || stack.getItem() instanceof net.minecraft.world.item.HoneyBottleItem;
    }

    public static Condition defaultCondition(ItemStack stack) {
        List<MobEffectInstance> effects = effects(stack);
        if (!effects.isEmpty()) {
            var type = effects.getFirst().getEffect();
            if (type.equals(MobEffects.WATER_BREATHING)) return Condition.UNDER_WATER;
            if (type.equals(MobEffects.HEAL) || type.equals(MobEffects.REGENERATION)) return Condition.HURT;
            if (type.equals(MobEffects.FIRE_RESISTANCE)) return Condition.ON_FIRE;
            if (type.equals(MobEffects.MOVEMENT_SPEED)) return Condition.SPRINTING;
            if (type.equals(MobEffects.DIG_SPEED)) return Condition.MINING;
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
            int elapsed = (int) (level.getGameTime() - pending.started());
            int duration = Math.max(1, current.getUseDuration(target));
            if (elapsed > (int) (duration * .21875F) && (duration - elapsed) % 4 == 0)
                emitUseEffects(level, target, current);
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
        String selection = upgrade.kind().advanced() ? NbtAccess.getStringOr(settings, "alchemy_targets", "BOTH") : "BOTH";
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
                        int duration = item.getUseDuration(target);
                        if (duration <= 0) continue;
                        long end = level.getGameTime() + duration;
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
        try { condition = Condition.valueOf(NbtAccess.getStringOr(settings, "alchemy_condition_" + row, defaultCondition(ghost).name())); }
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
                    && target.getHealth() / target.getMaxHealth() < Math.clamp(NbtAccess.getIntOr(settings, "alchemy_health_" + row, 75), 0, 100) / 100.0;
            case NEGATIVE_EFFECT -> target.getActiveEffects().stream().anyMatch(effect -> effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL);
        };
    }

    private static List<MobEffectInstance> effects(ItemStack item) {
        List<MobEffectInstance> effects = new ArrayList<>();
        PotionContents potion = item.get(DataComponents.POTION_CONTENTS);
        if (potion != null) potion.getAllEffects().forEach(effects::add);
        var food = item.get(DataComponents.FOOD);
        if (food != null) food.effects().stream().filter(effect -> effect.probability() > 0)
                .map(net.minecraft.world.food.FoodProperties.PossibleEffect::effect).forEach(effects::add);
        Integer omen = item.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
        if (omen != null) effects.add(new MobEffectInstance(MobEffects.BAD_OMEN, net.minecraft.world.item.OminousBottleItem.EFFECT_DURATION, omen));
        return effects;
    }

    private static boolean filterMatches(ItemStack item, ItemStack ghost, CompoundTag settings, boolean advanced) {
        if (!supported(item) || !ItemStack.isSameItem(item, ghost)) return false;
        boolean duration = !advanced || NbtAccess.getBooleanOr(settings, "alchemy_match_duration", true);
        boolean amplifier = !advanced || NbtAccess.getBooleanOr(settings, "alchemy_match_amplifier", true);
        boolean all = !advanced || NbtAccess.getBooleanOr(settings, "alchemy_match_all", true);
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
            if (effect.getEffect().value().isInstantenous()) return !effect.getEffect().equals(MobEffects.HEAL) || target.getHealth() < target.getMaxHealth();
            MobEffectInstance active = target.getEffect(effect.getEffect());
            return active == null || active.getAmplifier() < effect.getAmplifier();
        };
        boolean beneficial = !offered.isEmpty() && (NbtAccess.getBooleanOr(settings, "alchemy_all_missing", true)
                ? offered.stream().allMatch(missing) : offered.stream().anyMatch(missing));
        if (beneficial) return true;
        if (item.getItem() instanceof net.minecraft.world.item.MilkBucketItem && !target.getActiveEffects().isEmpty()) return true;
        return item.getItem() instanceof net.minecraft.world.item.HoneyBottleItem && target.hasEffect(MobEffects.POISON);
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
            SplashUse splash = new SplashUse(level, target, dose);
            if (carrier != null) splash.setOwner(carrier);
            splash.impact(target); // Native radius, duration scaling, instant effects, particles and disposal.
        } else ConsumptionRuntime.consumeOne(bag, storage, source, level, target);
    }

    /** Calls the native protected impact without manufacturing a network-visible projectile. */
    private static final class SplashUse extends ThrownPotion {
        private SplashUse(ServerLevel level, LivingEntity target, ItemStack dose) {
            super(level, target.getX(), target.getY(), target.getZ());
            setItem(dose);
        }
        private void impact(LivingEntity target) { super.onHit(new EntityHitResult(target)); }
    }

    /** Storage consumption has no held-use state; send its native item effects without altering either hand. */
    private static void emitUseEffects(ServerLevel level, LivingEntity target, ItemStack item) {
        var random = level.getRandom();
        if (item.getUseAnimation() == net.minecraft.world.item.UseAnim.DRINK) {
            level.playSound(null, target.getX(), target.getY(), target.getZ(), item.getDrinkingSound(), target.getSoundSource(), .5F, random.nextFloat() * .1F + .9F);
        } else if (item.getUseAnimation() == net.minecraft.world.item.UseAnim.EAT) {
            level.playSound(null, target.getX(), target.getY(), target.getZ(), item.getEatingSound(), target.getSoundSource(), .5F + .5F * random.nextInt(2), (random.nextFloat() - random.nextFloat()) * .2F + 1);
            var mouth = target.getEyePosition().add(target.getViewVector(1).scale(.4));
            level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(net.minecraft.core.particles.ParticleTypes.ITEM, item),
                    mouth.x, mouth.y - .2, mouth.z, 5, .12, .1, .12, .03);
        }
    }

    private static void clearProgress(BagInventory bag, InstalledUpgrade upgrade) {
        CompoundTag state = bag.settings(upgrade);
        if (NbtAccess.getIntOr(state, "alchemy_active_row", -1) != -1 || NbtAccess.getLongOr(state, "alchemy_finish", 0) != 0) {
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
