package com.kadamitas.fabricatedbackpacks.gametest;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.config.ConfigFile;
import com.kadamitas.fabricatedbackpacks.config.ServerConfig;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import com.kadamitas.fabricatedbackpacks.upgrade.JukeboxRuntime;
import com.kadamitas.fabricatedbackpacks.world.ChestLoot;
import com.kadamitas.fabricatedbackpacks.world.MobLoot;
import com.kadamitas.fabricatedbackpacks.world.WorldBackpacks;
import com.kadamitas.fabricatedbackpacks.world.WorldComponents;
import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorldGameTests {
    private WorldGameTests() { }

    private static ServerConfig rules(String changes) {
        JsonObject carrier = JsonParser.parseString("""
                {"spawnChance":1,"tierWeights":[1,0,0,0,0,0],"midMinimumTier":0,"highMinimumTier":0,
                 "health":false,"armor":false,"enchantments":false,"effects":false,"music":false,
                 "loot":false,"dropChance":1,"lootingBonus":0}
                """).getAsJsonObject();
        JsonParser.parseString(changes).getAsJsonObject().entrySet().forEach(entry -> carrier.add(entry.getKey(), entry.getValue()));
        JsonObject root = new JsonObject(); root.add("carriers", carrier);
        return ConfigFile.decode(root.toString());
    }

    private static Mob zombie(GameTestHelper helper) {
        var mob = helper.spawn(EntityType.ZOMBIE, new BlockPos(3, 1, 3));
        mob.setNoAi(true);
        mob.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        return mob;
    }

    public static void spawnLifecycle(GameTestHelper helper) {
        var enabled = rules("{}").carriers();
        Mob denied = zombie(helper);
        helper.assertFalse(WorldBackpacks.evaluate(denied, 0, rules("{\"spawnChance\":0}").carriers(), RandomSource.create(1)), "A zero spawn chance gives no backpack");
        helper.assertFalse(WorldBackpacks.evaluate(denied, 0, enabled, RandomSource.create(1)), "An evaluated mob cannot reroll after a rule change");
        denied.discard();
        Mob armored = zombie(helper);
        ItemStack chest = new ItemStack(Items.IRON_CHESTPLATE);
        armored.setItemSlot(EquipmentSlot.CHEST, chest);
        helper.assertFalse(WorldBackpacks.evaluate(armored, 0, enabled, RandomSource.create(1)), "Ordinary chest equipment is never overwritten");
        helper.assertTrue(armored.getItemBySlot(EquipmentSlot.CHEST) == chest, "Refusing a carrier preserves the physical armor stack");
        armored.discard();
        var raider = helper.spawn(EntityType.PILLAGER, new BlockPos(3, 1, 3));
        raider.setCurrentRaid(new Raid(1234567, helper.getLevel(), raider.blockPosition()));
        helper.assertFalse(WorldBackpacks.evaluate(raider, 0, enabled, RandomSource.create(1)), "Active raid participants never get backpacks");
        raider.discard();
        var cow = helper.spawn(EntityType.COW, new BlockPos(3, 1, 3));
        helper.assertFalse(WorldBackpacks.evaluate(cow, 0, enabled, RandomSource.create(1)), "Ordinary passive mobs are not carrier candidates");
        cow.discard();

        ServerConfig previous = BackpackConfig.get();
        try {
            BackpackConfig.configure(rules("{}"));
            Mob natural = zombie(helper);
            natural.finalizeSpawn(helper.getLevel(), new DifficultyInstance(Difficulty.NORMAL, 0, 0, 0), MobSpawnType.NATURAL, null);
            natural.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            helper.assertFalse(WorldBackpacks.isCarrier(natural), "The finalize hook defers assignment until vanilla equipment is finalized");
            natural.tick();
            helper.assertTrue(WorldBackpacks.isCarrier(natural), "The real Mob.tick injection performs the queued assignment");
            String identity = BagInventory.of(natural.getItemBySlot(EquipmentSlot.CHEST)).identity();
            natural.tick();
            helper.assertValueEqual(BagInventory.of(natural.getItemBySlot(EquipmentSlot.CHEST)).identity(), identity, "Subsequent ticks retain one carrier identity");
            var dropChances = natural.saveWithoutId(new CompoundTag()).getList("ArmorDropChances", net.minecraft.nbt.Tag.TAG_FLOAT);
            helper.assertValueEqual(dropChances.size(), 4, "Vanilla serializes every real armor-drop probability");
            helper.assertValueEqual(dropChances.getFloat(EquipmentSlot.CHEST.getIndex()), 0f, "Vanilla chest-drop probability is suppressed");
            natural.discard();
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }

    public static void carrierBuffsAndLootMappings(GameTestHelper helper) {
        var lootRules = rules("{\"loot\":true}").carriers();
        for (var mapping : lootRules.lootTables().entrySet()) {
            var type = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(mapping.getKey()));
            var created = type.create(helper.getLevel());
            helper.assertTrue(created instanceof Mob, "Mapped type is a real mob: " + mapping.getKey());
            Mob mob = (Mob)created;
            mob.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 1, 3))), 0, 0);
            helper.getLevel().addFreshEntity(mob);
            helper.assertTrue(WorldBackpacks.evaluate(mob, 0, lootRules, RandomSource.create(7)), "A mapped eligible monster receives its bag: " + mapping.getKey());
            BagInventory bag = BagInventory.of(mob.getItemBySlot(EquipmentSlot.CHEST));
            var plan = bag.stack().get(WorldComponents.DEFERRED_LOOT);
            helper.assertValueEqual(plan.table().toString(), mapping.getValue(), "The exact configured loot table is retained");
            helper.assertTrue(bag.isEmpty(), "Spawn assignment does not eagerly generate a distant mob's loot");
            mob.discard();
        }
        for (BackpackTier tier : BackpackTier.values()) {
            String weights = java.util.stream.IntStream.range(0, 6).mapToObj(i -> i == tier.ordinal() ? "1" : "0").collect(java.util.stream.Collectors.joining(","));
            var buffs = rules("{\"tierWeights\":[" + weights + "],\"health\":true,\"armor\":true,\"effects\":true,\"enchantments\":true}").carriers();
            Mob mob = zombie(helper);
            float before = mob.getMaxHealth();
            ItemStack existing = new ItemStack(Items.GOLDEN_HELMET);
            mob.setItemSlot(EquipmentSlot.HEAD, existing);
            helper.assertTrue(WorldBackpacks.evaluate(mob, 0, buffs, RandomSource.create(19)), "The configured tier is equipped");
            helper.assertValueEqual(BackpackRegistry.tier(mob.getItemBySlot(EquipmentSlot.CHEST)).orElseThrow(), tier, "Tier weights control real equipment");
            helper.assertTrue(Math.abs(mob.getMaxHealth() - before - 5 * (tier.ordinal() + 1)) < .0001, "Health scales once with the chosen tier");
            helper.assertTrue(mob.getItemBySlot(EquipmentSlot.HEAD) == existing, "Carrier armor does not replace existing equipment");
            helper.assertTrue(!mob.getItemBySlot(EquipmentSlot.FEET).isEmpty() && !mob.getItemBySlot(EquipmentSlot.LEGS).isEmpty(), "Enabled armor fills free slots");
            helper.assertValueEqual(mob.hasEffect(MobEffects.MOVEMENT_SPEED), tier.ordinal() >= 1, "Potion buffs follow tier thresholds");
            helper.assertTrue(mob.getItemBySlot(EquipmentSlot.HEAD).isEnchanted(), "Enabled enchantments use real vanilla enchantment data");
            mob.discard();
        }
        Mob plain = zombie(helper);
        float health = plain.getMaxHealth();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.LEGS, EquipmentSlot.FEET)) plain.setItemSlot(slot, ItemStack.EMPTY);
        helper.assertTrue(WorldBackpacks.evaluate(plain, 0, rules("{}").carriers(), RandomSource.create(1)), "A carrier can be generated with all optional bonuses disabled");
        helper.assertValueEqual(plain.getMaxHealth(), health, "Disabling health leaves attributes unchanged");
        helper.assertTrue(plain.getActiveEffects().isEmpty() && plain.getItemBySlot(EquipmentSlot.FEET).isEmpty(), "Disabled effects and armor do not run");
        plain.discard();
        helper.succeed();
    }

    public static void dropsAndFakePlayers(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        ServerPlayer player = BackpackTestSupport.player(helper);
        try {
            BackpackConfig.configure(rules("{\"loot\":true}"));
            Mob playerKilled = zombie(helper);
            WorldBackpacks.evaluate(playerKilled, 0, BackpackConfig.get().carriers(), RandomSource.create(1));
            var bounds = playerKilled.getBoundingBox().inflate(2);
            playerKilled.hurt(helper.getLevel().damageSources().playerAttack(player), 1000);
            List<ItemEntity> bags = bagDrops(helper, bounds);
            helper.assertValueEqual(bags.size(), 1, "One real player kill yields exactly one guaranteed bag");
            ItemStack reward = bags.getFirst().getItem();
            helper.assertTrue(reward.has(WorldComponents.DEFERRED_LOOT), "Killing a carrier preserves deferred loot without eager generation");
            helper.assertFalse(reward.has(BagComponents.IDENTITY), "The carrier's transient bag identity is cleared before collection");
            helper.assertTrue(playerKilled.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "The dropped bag is removed from the corpse");
            bags.forEach(ItemEntity::discard);
            playerKilled.discard();

            Mob environmental = zombie(helper);
            WorldBackpacks.evaluate(environmental, 0, BackpackConfig.get().carriers(), RandomSource.create(1));
            environmental.hurt(helper.getLevel().damageSources().genericKill(), 1000);
            helper.assertTrue(bagDrops(helper, bounds).isEmpty(), "Environmental kills do not roll carrier backpacks");
            environmental.discard();
            FakePlayer fake = FakePlayer.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "BackpackLootTest"));
            Mob fakeKilled = zombie(helper);
            WorldBackpacks.evaluate(fakeKilled, 0, BackpackConfig.get().carriers(), RandomSource.create(1));
            fakeKilled.hurt(helper.getLevel().damageSources().playerAttack(fake), 1000);
            helper.assertTrue(bagDrops(helper, bounds).isEmpty(), "Fake-player carrier drops are disabled by default");
            fakeKilled.discard();
            BackpackConfig.configure(rules("{\"fakePlayerDrops\":true}"));
            Mob allowedFake = zombie(helper);
            WorldBackpacks.evaluate(allowedFake, 0, BackpackConfig.get().carriers(), RandomSource.create(1));
            allowedFake.hurt(helper.getLevel().damageSources().playerAttack(fake), 1000);
            helper.assertValueEqual(bagDrops(helper, bounds).size(), 1, "Explicit fake-player opt-in enables one bag drop");
            bagDrops(helper, bounds).forEach(ItemEntity::discard); allowedFake.discard();

            BackpackConfig.configure(rules("{\"dropChance\":0,\"lootingBonus\":1}"));
            ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
            sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.LOOTING), 1);
            player.setItemSlot(EquipmentSlot.MAINHAND, sword);
            Mob looted = zombie(helper);
            WorldBackpacks.evaluate(looted, 0, BackpackConfig.get().carriers(), RandomSource.create(1));
            looted.hurt(helper.getLevel().damageSources().playerAttack(player), 1000);
            helper.assertValueEqual(bagDrops(helper, bounds).size(), 1, "The actual killer's Looting enchantment contributes to the drop roll");
            bagDrops(helper, bounds).forEach(ItemEntity::discard); looted.discard();

            BackpackConfig.configure(rules("{\"tierWeights\":[0,0,0,0,0,1]}"));
            Mob netherite = zombie(helper);
            WorldBackpacks.evaluate(netherite, 0, BackpackConfig.get().carriers(), RandomSource.create(1));
            netherite.hurt(helper.getLevel().damageSources().playerAttack(player), 1000);
            ItemEntity resistant = bagDrops(helper, bounds).getFirst();
            helper.assertFalse(resistant.hurt(helper.getLevel().damageSources().lava(), 100), "A real dropped netherite carrier bag resists fire damage");
            helper.assertFalse(resistant.isRemoved(), "Fire resistance retains the actual item entity");
            resistant.discard(); netherite.discard();
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }

    public static void conversionAndMusic(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        ServerPlayer player = BackpackTestSupport.player(helper);
        try {
            ServerConfig config = rules("{\"music\":true,\"musicChance\":1,\"advancedMusicChance\":1,\"health\":true,\"loot\":true}");
            BackpackConfig.configure(config);
            for (boolean keepEquipment : List.of(true, false)) {
                Mob source = zombie(helper);
                helper.assertTrue(WorldBackpacks.evaluate(source, 0, config.carriers(), RandomSource.create(77)), "A real carrier with music is created");
                BagInventory before = BagInventory.of(source.getItemBySlot(EquipmentSlot.CHEST));
                var music = before.installedUpgrades().stream().filter(upgrade -> upgrade.kind() == UpgradeKind.ADVANCED_JUKEBOX).findFirst().orElseThrow();
                var discs = before.upgradeInventory(music);
                List<ItemStack> originals = new ArrayList<>();
                for (int slot = 0; slot < discs.getContainerSize(); slot++) if (!discs.getItem(slot).isEmpty()) originals.add(discs.getItem(slot).copy());
                helper.assertTrue(originals.size() >= 1 && originals.size() <= 4, "Advanced carrier music contains one to four discs");
                helper.assertValueEqual(new HashSet<>(originals.stream().map(ItemVariant::of).toList()).size(), originals.size(), "Carrier discs are distinct");
                WorldBackpacks.tick(source);
                helper.assertTrue(NbtAccess.getBooleanOr(before.settings(music), "playing", false), "Carrier ticks start the real moving jukebox session");
                String identity = before.identity();
                var lootPlan = before.stack().get(WorldComponents.DEFERRED_LOOT);
                source.setHealth(source.getMaxHealth() / 2);
                Mob converted = convertWithDestinationArmor(source, keepEquipment);
                helper.assertTrue(converted != null && source.isRemoved(), "Vanilla conversion replaces the source entity");
                BagInventory after = BagInventory.of(converted.getItemBySlot(EquipmentSlot.CHEST));
                helper.assertValueEqual(after.identity(), identity, "Conversion retains the bag's identity");
                helper.assertValueEqual(after.stack().get(WorldComponents.DEFERRED_LOOT), lootPlan, "Conversion preserves its exact unrolled loot plan");
                helper.assertTrue(converted.getAttribute(Attributes.MAX_HEALTH).getModifier(WorldBackpacks.HEALTH_BONUS) != null, "Carrier health modifiers transfer to the converted mob");
                helper.assertTrue(Math.abs(converted.getHealth() / converted.getMaxHealth() - .5) < .0001, "Conversion preserves proportional health");
                if (!keepEquipment) helper.assertTrue(after.stack().getOrDefault(WorldComponents.EXTRA_ITEMS, InventorySnapshot.EMPTY).items().stream().anyMatch(item -> item.is(Items.IRON_CHESTPLATE)),
                        "A generated destination chestplate is preserved instead of overwritten");
                WorldBackpacks.tick(converted);
                var currentMusic = after.installedUpgrades().getFirst();
                helper.assertTrue(NbtAccess.getBooleanOr(after.settings(currentMusic), "playing", false), "Music restarts against the converted carrier");
                var bounds = converted.getBoundingBox().inflate(2);
                converted.hurt(helper.getLevel().damageSources().playerAttack(player), 1000);
                ItemEntity drop = bagDrops(helper, bounds).getFirst();
                List<ItemStack> extras = drop.getItem().getOrDefault(WorldComponents.EXTRA_ITEMS, InventorySnapshot.EMPTY).items();
                helper.assertValueEqual(extras.stream().filter(JukeboxRuntime::isDisc).mapToInt(ItemStack::getCount).sum(), originals.size(), "Death preserves every generated disc exactly once");
                helper.assertValueEqual(extras.stream().filter(item -> BackpackRegistry.kind(item).orElse(null) == UpgradeKind.ADVANCED_JUKEBOX).mapToInt(ItemStack::getCount).sum(), 1,
                        "The generated jukebox is retained as one reward item");
                helper.assertTrue(drop.getItem().getOrDefault(BagComponents.UPGRADES, InventorySnapshot.EMPTY).items().isEmpty(), "The carrier jukebox no longer remains installed as a second copy");
                ItemStack jukebox = extras.stream().filter(item -> BackpackRegistry.kind(item).orElse(null) == UpgradeKind.ADVANCED_JUKEBOX).findFirst().orElseThrow();
                helper.assertTrue(jukebox.getOrDefault(BagComponents.CONTENTS, InventorySnapshot.EMPTY).items().isEmpty(), "Moved discs are not duplicated inside the moved upgrade");
                drop.discard(); converted.discard();
            }
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }

    public static void delayedLootConservation(GameTestHelper helper) {
        ServerConfig previous = BackpackConfig.get();
        try {
            BackpackConfig.configure(ConfigFile.decode("{\"capacities\":{\"backpack\":{\"slots\":1,\"upgrades\":0}}}"));
            BagInventory full = BagInventory.of(new ItemStack(BackpackRegistry.item(BackpackTier.LEATHER)));
            full.setItem(0, new ItemStack(Items.STONE, 64));
            var plan = new WorldComponents.DeferredLoot(ResourceLocation.withDefaultNamespace("chests/spawn_bonus_chest"), 132459L, 2, 0);
            full.stack().set(WorldComponents.DEFERRED_LOOT, plan);
            BagInventory control = BagInventory.of(new ItemStack(BackpackRegistry.item(BackpackTier.NETHERITE)));
            control.stack().set(WorldComponents.DEFERRED_LOOT, plan);
            BlockPos position = helper.absolutePos(new BlockPos(3, 1, 3));
            helper.assertTrue(MobLoot.materialize(control, helper.getLevel(), position, null), "The vanilla loot table is rolled for the control bag");
            Map<ItemVariant, Long> expected = total(control);
            expected.merge(ItemVariant.of(Items.STONE), 64L, Long::sum);
            helper.assertTrue(MobLoot.materialize(full, helper.getLevel(), position, null), "A full bag still consumes its plan into a durable overflow queue");
            helper.assertValueEqual(total(full), expected, "Full storage plus overflow exactly conserves the deterministic reward");
            helper.assertFalse(full.stack().has(WorldComponents.DEFERRED_LOOT), "The plan is consumed once");
            helper.assertFalse(MobLoot.materialize(full, helper.getLevel(), position, null), "Repeated access does not reroll or lose blocked rewards");
            BagInventory restored = BagInventory.of(BackpackTestSupport.roundTrip(helper.getLevel(), full.stack()));
            helper.assertValueEqual(total(restored), expected, "The exact overflow resource multiset survives item serialization");
            var player = BackpackTestSupport.player(helper);
            MobLoot.materialize(restored, helper.getLevel(), position, player);
            Map<ItemVariant, Long> delivered = total(restored);
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) add(delivered, player.getInventory().getItem(slot));
            for (ItemEntity item : helper.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(2))) add(delivered, item.getItem());
            helper.assertValueEqual(delivered, expected, "Opening hands every blocked reward to the player without loss or duplication");
            helper.assertFalse(restored.stack().has(WorldComponents.EXTRA_ITEMS), "Delivered extras no longer remain in a hidden queue");
        } finally { BackpackConfig.configure(previous); }
        helper.succeed();
    }

    public static void creeperCloudProtection(GameTestHelper helper) {
        var carrier = helper.spawn(EntityType.CREEPER, new BlockPos(2, 1, 2));
        carrier.setNoAi(true); carrier.setInvulnerable(true);
        helper.assertTrue(WorldBackpacks.evaluate(carrier, 0, rules("{}").carriers(), RandomSource.create(2)), "The test creeper wears a real carrier bag");
        carrier.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 0));
        carrier.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
        var ordinary = helper.spawn(EntityType.CREEPER, new BlockPos(6, 1, 6));
        ordinary.setNoAi(true); ordinary.setInvulnerable(true);
        WorldBackpacks.evaluate(ordinary, 0, rules("{\"spawnChance\":0}").carriers(), RandomSource.create(2));
        ordinary.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 200, 0));
        carrier.ignite(); ordinary.ignite();
        helper.runAtTickTime(38, () -> {
            List<AreaEffectCloud> clouds = helper.getLevel().getEntitiesOfClass(AreaEffectCloud.class,
                    carrier.getBoundingBox().inflate(8));
            AreaEffectCloud protectedCloud = clouds.stream().filter(cloud -> cloud.position().distanceToSqr(carrier.position()) < 1).findFirst().orElseThrow();
            AreaEffectCloud vanillaCloud = clouds.stream().filter(cloud -> cloud.position().distanceToSqr(ordinary.position()) < 1).findFirst().orElseThrow();
            var output = new CompoundTag();
            protectedCloud.save(output);
            PotionContents protectedEffects = PotionContents.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess()),
                    NbtAccess.getCompoundOrEmpty(output, "potion_contents")).getOrThrow();
            helper.assertTrue(java.util.stream.StreamSupport.stream(protectedEffects.getAllEffects().spliterator(), false).anyMatch(effect -> effect.getEffect().equals(MobEffects.POISON)),
                    "A carrier creeper retains harmful effects in its real explosion cloud");
            helper.assertFalse(java.util.stream.StreamSupport.stream(protectedEffects.getAllEffects().spliterator(), false).anyMatch(effect -> effect.getEffect().equals(MobEffects.MOVEMENT_SPEED)),
                    "Carrier bonuses do not become beneficial area-cloud rewards");
            var vanillaOutput = new CompoundTag();
            vanillaCloud.save(vanillaOutput);
            PotionContents vanillaEffects = PotionContents.CODEC.parse(RegistryOps.create(NbtOps.INSTANCE, helper.getLevel().registryAccess()),
                    NbtAccess.getCompoundOrEmpty(vanillaOutput, "potion_contents")).getOrThrow();
            helper.assertTrue(java.util.stream.StreamSupport.stream(vanillaEffects.getAllEffects().spliterator(), false).anyMatch(effect -> effect.getEffect().equals(MobEffects.MOVEMENT_SPEED)),
                    "An ordinary creeper's vanilla beneficial cloud is unchanged");
            clouds.forEach(AreaEffectCloud::discard);
            helper.succeed();
        });
    }

    public static void chestLootTables(GameTestHelper helper) {
        ChestLootAcceptance.verify(helper.getLevel(), helper.absolutePos(new BlockPos(3, 1, 3)), false);
        helper.succeed();
    }

    private static Mob destinationArmorSource;
    private static boolean conversionFixtureRegistered;

    private static Mob convertWithDestinationArmor(Mob source, boolean keepEquipment) {
        // 1.21.1 has no postprocessor argument on Mob.convertTo. This scoped test-only
        // Fabric phase supplies native destination armor before the mod's normal conversion listener.
        if (!conversionFixtureRegistered) {
            var event = net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.MOB_CONVERSION;
            ResourceLocation phase = ResourceLocation.fromNamespaceAndPath("fabricated_backpacks_tests", "destination_armor");
            event.addPhaseOrdering(phase, net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE);
            event.register(phase, (previous, converted, retainedEquipment) -> {
                if (previous == destinationArmorSource && !retainedEquipment)
                    converted.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            });
            conversionFixtureRegistered = true;
        }
        destinationArmorSource = source;
        try { return source.convertTo(EntityType.DROWNED, keepEquipment); }
        finally { destinationArmorSource = null; }
    }

    private static List<ItemEntity> bagDrops(GameTestHelper helper, net.minecraft.world.phys.AABB bounds) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, bounds, item -> !item.isRemoved() && BackpackRegistry.isBackpack(item.getItem()));
    }
    private static Map<ItemVariant, Long> total(BagInventory bag) {
        Map<ItemVariant, Long> result = new HashMap<>();
        for (int slot = 0; slot < bag.getContainerSize(); slot++) add(result, bag.getItem(slot));
        bag.stack().getOrDefault(WorldComponents.EXTRA_ITEMS, InventorySnapshot.EMPTY).items().forEach(item -> add(result, item));
        return result;
    }
    private static void add(Map<ItemVariant, Long> values, ItemStack stack) {
        if (!stack.isEmpty()) values.merge(ItemVariant.of(stack), (long)stack.getCount(), Long::sum);
    }
}
