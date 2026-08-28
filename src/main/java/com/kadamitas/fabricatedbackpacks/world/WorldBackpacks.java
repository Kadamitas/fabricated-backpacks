package com.kadamitas.fabricatedbackpacks.world;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.config.RuleMatchers;
import com.kadamitas.fabricatedbackpacks.config.ServerConfig;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.upgrade.JukeboxRuntime;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;

import java.util.ArrayList;
import java.util.List;

/** Server-only carrier lifecycle. Ordinary chest equipment never rolls a second copy of its bag. */
public final class WorldBackpacks {
    public static final ResourceLocation HEALTH_BONUS = BackpackRegistry.id("carrier_health");
    private static final String CARRIER = "spawned_carrier";
    private WorldBackpacks() { }

    public static void initialize() {
        WorldComponents.initialize();
        ChestLoot.initialize();
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof Mob mob) drop(mob, source);
        });
        ServerLivingEntityEvents.MOB_CONVERSION.register(WorldBackpacks::converted);
    }

    public static void onFinalize(Mob mob, DifficultyInstance difficulty, MobSpawnType reason) {
        if (mob.level().isClientSide() || mob.getType().getCategory() != MobCategory.MONSTER
                || reason == MobSpawnType.CONVERSION
                || mob.getAttachedOrElse(WorldComponents.SPAWN_CHECKED, false)) return;
        // Subclasses add ordinary armor after Mob.finalizeSpawn returns; inspect equipment next tick.
        mob.setAttached(WorldComponents.PENDING_DIFFICULTY, difficulty.getEffectiveDifficulty());
    }

    public static void tick(Mob mob) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        Float pending = mob.getAttached(WorldComponents.PENDING_DIFFICULTY);
        if (pending != null) {
            mob.removeAttached(WorldComponents.PENDING_DIFFICULTY);
            evaluate(mob, pending, BackpackConfig.get().carriers(), mob.getRandom());
        }
        if (!mob.isAlive() || !isCarrier(mob)) return;
        BagInventory bag = BagInventory.of(mob.getItemBySlot(EquipmentSlot.CHEST));
        mob.setDropChance(EquipmentSlot.CHEST, 0);
        for (InstalledUpgrade upgrade : bag.installedUpgrades()) {
            if (!upgrade.kind().family().equals("jukebox")) continue;
            if (!BackpackConfig.get().carriers().music() || !NbtAccess.getBooleanOr(bag.settings(upgrade), "enabled", true)) {
                JukeboxRuntime.stopUpgrade(bag, upgrade.slot(), level.getServer());
                continue;
            }
            JukeboxRuntime.tick(bag, upgrade, level, mob.blockPosition(), mob);
            if (!NbtAccess.getBooleanOr(bag.settings(upgrade), "playing", false))
                JukeboxRuntime.action(bag, upgrade, level, mob.blockPosition(), mob, "play");
        }
    }

    /** Deterministic entry point used by the deferred spawn hook and server tests. Never rerolls a mob. */
    public static boolean evaluate(Mob mob, double difficulty, ServerConfig.Carriers rules, RandomSource random) {
        rules.minimumTier(difficulty); // Validate before changing the persisted evaluation marker.
        if (!(mob.level() instanceof ServerLevel) || mob.getAttachedOrElse(WorldComponents.SPAWN_CHECKED, false)) return false;
        mob.setAttached(WorldComponents.SPAWN_CHECKED, true);
        if (!mob.isAlive() || mob.getType().getCategory() != MobCategory.MONSTER || !mob.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                || mob instanceof Raider raider && raider.hasRaid() || random.nextDouble() >= rules.spawnChance()) return false;
        BackpackTier tier = CarrierSelection.choose(rules, difficulty, random::nextInt).orElse(null);
        if (tier == null) return false;
        BagInventory bag = BagInventory.of(new ItemStack(BackpackRegistry.item(tier)));
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
        ServerConfig.Colors colors = rules.colors().get(type);
        if (colors != null) bag.dye(colors.body(), colors.trim());
        double healthBonus = rules.health() ? rules.healthPerTier() * (tier.ordinal() + 1) : 0;
        bag.updateSettings(tag -> {
            tag.putBoolean(CARRIER, true);
            tag.putString("spawn_source", type);
            tag.putDouble("spawn_difficulty", difficulty);
            tag.putDouble("spawn_health", healthBonus);
        });
        String loot = rules.lootTables().get(type);
        if (rules.loot() && loot != null) bag.stack().set(WorldComponents.DEFERRED_LOOT,
                new WorldComponents.DeferredLoot(ResourceLocation.parse(loot), random.nextLong(),
                        Math.clamp(1 + (int)(difficulty / 2) + tier.ordinal() / 2, 1, 6), (float)difficulty));
        if (rules.music() && random.nextDouble() < rules.musicChance()) addMusic(bag, rules, random);
        applyHealth(mob, healthBonus, mob.getHealth() / mob.getMaxHealth());
        if (rules.effects()) addEffects(mob, tier);
        if (rules.armor()) addArmor(mob, tier);
        if (rules.enchantments()) enchantEquipment(mob, tier);
        bag.save();
        mob.setItemSlot(EquipmentSlot.CHEST, bag.stack());
        mob.setDropChance(EquipmentSlot.CHEST, 0);
        return true;
    }

    public static boolean isCarrier(Mob mob) {
        ItemStack chest = mob.getItemBySlot(EquipmentSlot.CHEST);
        return BackpackRegistry.isBackpack(chest) && NbtAccess.getBooleanOr(chest.getOrDefault(BagComponents.SETTINGS, CustomData.EMPTY).copyTag(), CARRIER, false);
    }

    private static void addMusic(BagInventory bag, ServerConfig.Carriers rules, RandomSource random) {
        if (bag.upgrades().getContainerSize() == 0) return;
        List<ItemStack> discs = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack disc = new ItemStack(item);
            if (JukeboxRuntime.isDisc(disc) && !RuleMatchers.item(disc, rules.blockedDiscs())) discs.add(disc);
        }
        if (discs.isEmpty()) return;
        UpgradeKind kind = random.nextDouble() < rules.advancedMusicChance() ? UpgradeKind.ADVANCED_JUKEBOX : UpgradeKind.JUKEBOX;
        ItemStack upgrade = new ItemStack(BackpackRegistry.item(kind));
        if (!bag.canInstall(0, upgrade)) return;
        bag.upgrades().setItem(0, upgrade);
        InstalledUpgrade installed = bag.installedUpgrades().getFirst();
        var contents = bag.upgradeInventory(installed);
        int count = kind.advanced() ? 1 + random.nextInt(Math.min(rules.maximumDiscs(), Math.min(discs.size(), contents.getContainerSize()))) : 1;
        for (int slot = 0; slot < count; slot++) contents.setItem(slot, discs.remove(random.nextInt(discs.size())));
        bag.updateSettings(installed, tag -> tag.putString("repeat", "ALL"));
        bag.updateSettings(tag -> tag.putInt("spawn_music_slot", installed.slot()));
    }

    private static void applyHealth(Mob mob, double amount, float fraction) {
        var attribute = mob.getAttribute(Attributes.MAX_HEALTH);
        if (attribute == null) return;
        attribute.removeModifier(HEALTH_BONUS);
        if (amount > 0) attribute.addPermanentModifier(new AttributeModifier(HEALTH_BONUS, amount, AttributeModifier.Operation.ADD_VALUE));
        mob.setHealth(mob.getMaxHealth() * Math.clamp(fraction, 0, 1));
    }

    private static void addEffects(Mob mob, BackpackTier tier) {
        if (tier.ordinal() >= 1) effect(mob, MobEffects.MOVEMENT_SPEED);
        if (tier.ordinal() >= 2) effect(mob, MobEffects.DAMAGE_RESISTANCE);
        if (tier.ordinal() >= 4) effect(mob, MobEffects.DAMAGE_BOOST);
        if (tier == BackpackTier.NETHERITE) effect(mob, MobEffects.FIRE_RESISTANCE);
    }
    private static void effect(Mob mob, Holder<MobEffect> effect) { mob.addEffect(new MobEffectInstance(effect, -1, 0, true, false)); }

    private static void addArmor(Mob mob, BackpackTier tier) {
        Item[] pieces = switch (tier) {
            case LEATHER -> new Item[]{Items.LEATHER_HELMET, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
            case COPPER -> new Item[]{Items.CHAINMAIL_HELMET, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS};
            case IRON -> new Item[]{Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
            case GOLD -> new Item[]{Items.GOLDEN_HELMET, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS};
            case DIAMOND -> new Item[]{Items.DIAMOND_HELMET, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
            case NETHERITE -> new Item[]{Items.NETHERITE_HELMET, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
        };
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int index = 0; index < slots.length; index++) if (mob.getItemBySlot(slots[index]).isEmpty()) mob.setItemSlot(slots[index], new ItemStack(pieces[index]));
    }

    private static void enchantEquipment(Mob mob, BackpackTier tier) {
        int level = Math.min(3, 1 + tier.ordinal() / 2);
        var protection = mob.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            ItemStack item = mob.getItemBySlot(slot);
            if (!item.isEmpty() && protection.value().canEnchant(item)) item.enchant(protection, level);
        }
        var damage = mob.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS);
        ItemStack weapon = mob.getMainHandItem();
        if (!weapon.isEmpty() && damage.value().canEnchant(weapon)) weapon.enchant(damage, level);
    }

    private static void drop(Mob mob, DamageSource source) {
        if (!(mob.level() instanceof ServerLevel level) || !isCarrier(mob)) return;
        BagInventory original = BagInventory.of(mob.getItemBySlot(EquipmentSlot.CHEST));
        if (NbtAccess.getBooleanOr(original.settings(), "spawn_death_handled", false)) return;
        original.updateSettings(tag -> tag.putBoolean("spawn_death_handled", true));
        for (InstalledUpgrade upgrade : original.installedUpgrades()) if (upgrade.kind().family().equals("jukebox"))
            JukeboxRuntime.stopUpgrade(original, upgrade.slot(), level.getServer());
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOMOBLOOT)) return;
        Player killer = source.getEntity() instanceof Player player ? player
                : mob instanceof com.kadamitas.fabricatedbackpacks.upgrade.UpgradeAccess.LastPlayerDamage damage
                && damage.fabricatedBackpacks$lastPlayerDamageTicks() > 0 ? damage.fabricatedBackpacks$lastPlayerDamager() : null;
        ServerConfig.Carriers rules = BackpackConfig.get().carriers();
        if (killer == null || killer instanceof FakePlayer && !rules.fakePlayerDrops()) return;
        var looting = mob.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.LOOTING);
        int lootingLevel = EnchantmentHelper.getEnchantmentLevel(looting, killer);
        if (mob.getRandom().nextDouble() >= rules.effectiveDropChance(original.tier(), lootingLevel)) return;
        BagInventory reward = BagInventory.of(original.stack().copy());
        prepareReward(reward);
        if (mob.spawnAtLocation(reward.stack()) != null) mob.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
    }

    private static void prepareReward(BagInventory bag) {
        int musicSlot = NbtAccess.getIntOr(bag.settings(), "spawn_music_slot", -1);
        InstalledUpgrade music = bag.installedUpgrades().stream().filter(upgrade -> upgrade.slot() == musicSlot && upgrade.kind().family().equals("jukebox")).findFirst().orElse(null);
        if (music != null) {
            List<ItemStack> extra = new ArrayList<>();
            var discs = bag.upgradeInventory(music);
            for (int slot = 0; slot < discs.getContainerSize(); slot++) if (!discs.getItem(slot).isEmpty()) extra.add(discs.getItem(slot).copy());
            discs.clearContent();
            bag.updateSettings(music, tag -> {
                tag.remove("jukebox_identity"); tag.remove("playing"); tag.remove("active_slot");
                tag.remove("song_started"); tag.remove("song_finish");
            });
            extra.add(music.stack().copy());
            MobLoot.queue(bag, extra);
            bag.upgrades().setItem(music.slot(), ItemStack.EMPTY);
        }
        bag.updateSettings(tag -> {
            tag.remove(CARRIER); tag.remove("spawn_music_slot"); tag.remove("spawn_death_handled");
            tag.remove("spawn_health"); tag.remove("spawn_source"); tag.remove("spawn_difficulty");
        });
        bag.stack().remove(BagComponents.IDENTITY);
        bag.save();
    }

    private static void converted(Mob previous, Mob converted, boolean keepEquipment) {
        // The 1.21.1 Fabric callback covers native Mob.convertTo, which always replaces the original mob.
        boolean oldCarrier = isCarrier(previous);
        boolean newCarrier = isCarrier(converted);
        if (!oldCarrier && !newCarrier) return;
        if (oldCarrier && !newCarrier) {
            ItemStack oldBag = previous.getItemBySlot(EquipmentSlot.CHEST);
            BagInventory bag = BagInventory.of(oldBag);
            ItemStack replaced = converted.getItemBySlot(EquipmentSlot.CHEST);
            if (!replaced.isEmpty()) MobLoot.queue(bag, List.of(replaced));
            converted.setItemSlot(EquipmentSlot.CHEST, oldBag);
            previous.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        }
        BagInventory bag = BagInventory.of(converted.getItemBySlot(EquipmentSlot.CHEST));
        if (previous.level() instanceof ServerLevel level) for (InstalledUpgrade upgrade : bag.installedUpgrades())
            if (upgrade.kind().family().equals("jukebox")) JukeboxRuntime.stopUpgrade(bag, upgrade.slot(), level.getServer());
        float fraction = previous.getMaxHealth() <= 0 ? 1 : previous.getHealth() / previous.getMaxHealth();
        applyHealth(converted, NbtAccess.getDoubleOr(bag.settings(), "spawn_health", 0), fraction);
        converted.setDropChance(EquipmentSlot.CHEST, 0);
        converted.setAttached(WorldComponents.SPAWN_CHECKED, true);
        converted.removeAttached(WorldComponents.PENDING_DIFFICULTY);
    }

    /** Do not turn a carrier's permanent combat buffs into a collectible beneficial creeper cloud. */
    public static void beforeCreeperCloud(Mob creeper) {
        if (!isCarrier(creeper)) return;
        List<Holder<MobEffect>> beneficial = creeper.getActiveEffects().stream().map(MobEffectInstance::getEffect)
                .filter(effect -> effect.value().isBeneficial()).toList();
        beneficial.forEach(creeper::removeEffect);
    }
}
