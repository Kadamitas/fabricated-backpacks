package com.kadamitas.fabricatedbackpacks.upgrade;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/** Server-authoritative entry points. UI packets may select actions, never supply items, recipes, or results. */
public final class UpgradeEngine {
    private record Key(MinecraftServer server, String identity) { }
    private static final class Context {
        ServerLevel level;
        BlockPos position;
        LivingEntity carrier;
        int lastTick = Integer.MIN_VALUE;
        int lastSeen;
        boolean inserted;
        final Set<Integer> manualChanges = new HashSet<>();
    }
    private static final Map<Key, Context> CONTEXTS = new HashMap<>();
    private static final Set<String> INSERTED = new HashSet<>();
    private static final Map<String, Set<Integer>> MANUAL_CHANGES = new HashMap<>();
    private static final List<BiPredicate<ItemEntity, LivingEntity>> MOVEMENT_BLOCKERS = new ArrayList<>();
    private static boolean alwaysVoidAllowed = true;
    private static final Set<String> FIXED_UPGRADES = Set.of("stack", "tank", "battery", "crafting", "stonecutter", "anvil", "smithing", "infinity", "inception", "mob_catcher", "everlasting");
    private UpgradeEngine() { }

    public static void setSoundBridge(JukeboxRuntime.SoundBridge bridge) { JukeboxRuntime.setSoundBridge(bridge); }
    public static void allowAlwaysVoiding(boolean allowed) { alwaysVoidAllowed = allowed; }
    public static void registerMovementBlocker(BiPredicate<ItemEntity, LivingEntity> blocker) { MOVEMENT_BLOCKERS.add(java.util.Objects.requireNonNull(blocker)); }

    public static void tick(BagInventory bag, ServerLevel level, BlockPos position, LivingEntity carrier) {
        Key key = new Key(level.getServer(), bag.identity());
        Context context = CONTEXTS.computeIfAbsent(key, ignored -> new Context());
        context.level = level;
        context.position = position.immutable();
        context.carrier = carrier;
        context.lastSeen = level.getServer().getTickCount();
        if (context.lastTick == context.lastSeen) return;
        context.lastTick = context.lastSeen;
        context.inserted |= INSERTED.remove(bag.identity());
        Set<Integer> manual = MANUAL_CHANGES.remove(bag.identity());
        if (manual != null) context.manualChanges.addAll(manual);
        for (InstalledUpgrade upgrade : bag.installedUpgrades()) {
            if (!UpgradeFilters.enabled(bag, upgrade)) {
                JukeboxRuntime.stopUpgrade(bag, upgrade.slot(), level.getServer());
                AlchemyRuntime.cancel(bag, upgrade.slot(), level.getServer());
                if (bag.settings(upgrade).getBooleanOr("burning", false)) bag.updateSettings(upgrade, tag -> tag.putBoolean("burning", false));
                continue;
            }
            switch (upgrade.kind().family()) {
                case "cooking" -> CookingRuntime.tick(bag, upgrade, level);
                case "jukebox" -> JukeboxRuntime.tick(bag, upgrade, level, position, carrier);
                case "magnet" -> magnet(bag, upgrade, level, position, carrier);
                case "feeding" -> ConsumptionRuntime.feed(bag, upgrade, level, position, carrier);
                case "refill" -> TransferRuntime.refill(bag, upgrade, level, position, carrier);
                case "alchemy" -> AlchemyRuntime.tick(bag, upgrade, level, position, carrier);
                case "compacting" -> {
                    var rules = BackpackConfig.get().upgrades().compacting();
                    if ((context.inserted || bag.settings(upgrade).getBooleanOr("work_in_gui", false)) && level.getGameTime() % rules.interval() == 0) {
                        CompactingRuntime.compact(bag, upgrade, level, rules.maximumOperations());
                    }
                }
                case "void" -> voidManualChanges(bag, upgrade, context.manualChanges);
                default -> { /* Storage, workstations, protection and resource adapters own their separate entry points. */ }
            }
        }
        context.manualChanges.clear();
    }

    public static boolean acceptsInput(BagInventory bag, ItemStack stack) { return accepts(bag, stack, true); }
    public static boolean acceptsOutput(BagInventory bag, ItemStack stack) { return accepts(bag, stack, false); }
    private static boolean accepts(BagInventory bag, ItemStack stack, boolean input) {
        if (stack.isEmpty()) return false;
        for (InstalledUpgrade upgrade : bag.installedUpgrades()) {
            if (!upgrade.kind().family().equals("filter") || !UpgradeFilters.enabled(bag, upgrade)) continue;
            String direction = bag.settings(upgrade).getStringOr("filter_direction", "BOTH");
            if (direction.equals(input ? "OUTPUT" : "INPUT")) continue;
            if (!UpgradeFilters.matches(bag, upgrade, stack)) return false;
        }
        return true;
    }

    /** Raw bag.insert supplies structural/capacity checks; this external route also applies intentional voiding. */
    public static ItemStack insert(BagInventory bag, ItemStack supplied, boolean simulate) {
        if (supplied.isEmpty()) return ItemStack.EMPTY;
        if (simulate && BackpackTraversal.usesChildren(bag)) bag = BackpackTraversal.simulationCopy(bag);
        if (!acceptsInput(bag, supplied)) return supplied.copy();
        BagInventory owner = bag;
        InstalledUpgrade voider = BackpackRegistry.isBackpack(supplied) ? null : bag.installedUpgrades().stream()
                .filter(upgrade -> upgrade.kind().family().equals("void") && UpgradeFilters.enabled(owner, upgrade)
                        && UpgradeFilters.matches(owner, upgrade, supplied)).findFirst().orElse(null);
        ItemStack remainder;
        if (voider == null) remainder = BackpackTraversal.insert(bag, supplied, simulate, null);
        else {
            String mode = voidMode(bag.settings(voider));
            if (mode.equals("ALWAYS")) remainder = ItemStack.EMPTY;
            else if (mode.equals("SLOT_OVERFLOW")) remainder = insertSlotOverflow(bag, voider, supplied, simulate);
            else {
                BackpackTraversal.insert(bag, supplied, simulate, null);
                remainder = ItemStack.EMPTY;
            }
        }
        if (!simulate && remainder.getCount() < supplied.getCount()) INSERTED.add(bag.identity());
        return remainder;
    }

    public static String voidMode(CompoundTag settings) {
        String mode = settings.getStringOr("void_mode", "STORAGE_OVERFLOW");
        if (!Set.of("ALWAYS", "SLOT_OVERFLOW", "STORAGE_OVERFLOW").contains(mode)) return "STORAGE_OVERFLOW";
        return mode.equals("ALWAYS") && (!alwaysVoidAllowed || !BackpackConfig.get().upgrades().allowAlwaysVoid()) ? "STORAGE_OVERFLOW" : mode;
    }

    private static ItemStack insertSlotOverflow(BagInventory bag, InstalledUpgrade upgrade, ItemStack supplied, boolean simulate) {
        CompoundTag settings = bag.settings(upgrade);
        boolean damage = settings.getBooleanOr("match_damage", false);
        boolean components = settings.getBooleanOr("match_components", false);
        long represented = 0;
        for (ItemStack present : BackpackTraversal.processingInventory(bag)) if (UpgradeFilters.same(supplied, present, "ITEM", damage, components)) represented += present.getCount();
        int allowance = (int) Math.max(0, bag.capacity(supplied) - Math.min(Integer.MAX_VALUE, represented));
        int attempt = Math.min(supplied.getCount(), allowance);
        ItemStack retained = attempt == 0 ? ItemStack.EMPTY : BackpackTraversal.insert(bag, supplied.copyWithCount(attempt), simulate, null);
        int inserted = attempt - retained.getCount();
        // Never destroy the first representation if storage cannot accept even one item.
        if (represented == 0 && inserted == 0) return supplied.copy();
        return retained;
    }

    /** Notify only genuinely changed GUI slots; changed/emptied slots are revalidated before ALWAYS deletion. */
    public static void onManualSlotChanged(BagInventory bag, int slot) {
        if (slot >= 0 && slot < bag.getContainerSize()) MANUAL_CHANGES.computeIfAbsent(bag.identity(), ignored -> new HashSet<>()).add(slot);
    }

    public static void onExternalInsertion(BagInventory bag) { INSERTED.add(bag.identity()); }

    public static void pause(BagInventory bag, MinecraftServer server) {
        for (InstalledUpgrade upgrade : bag.installedUpgrades()) {
            stopUpgrade(bag, upgrade.slot(), server);
            if (bag.settings(upgrade).getBooleanOr("burning", false)) bag.updateSettings(upgrade, tag -> tag.putBoolean("burning", false));
        }
    }

    private static void voidManualChanges(BagInventory bag, InstalledUpgrade upgrade, Set<Integer> changed) {
        if (!alwaysVoidAllowed || !bag.settings(upgrade).getBooleanOr("work_in_gui", false) || !voidMode(bag.settings(upgrade)).equals("ALWAYS")) return;
        for (int slot : changed) {
            ItemStack stack = bag.getItem(slot);
            if (!stack.isEmpty() && !BackpackRegistry.isBackpack(stack) && UpgradeFilters.matches(bag, upgrade, stack)) bag.setItem(slot, ItemStack.EMPTY);
        }
    }

    /** True only when the item entity was fully handled; partial pickup leaves a valid vanilla remainder. */
    public static boolean pickup(BagInventory bag, ItemEntity item, ServerPlayer player) {
        if (!canCollect(item, player, false)) return false;
        for (var node : BackpackTraversal.upgradeBags(bag)) {
            if (!node.attached()) continue;
            var before = node.inventory().stack().getComponentsPatch();
            boolean handled = pickupLocal(node.inventory(), item, player);
            if (node.parent() != null && !before.equals(node.inventory().stack().getComponentsPatch())) node.persist();
            if (handled) return true;
        }
        return false;
    }

    private static boolean pickupLocal(BagInventory bag, ItemEntity item, ServerPlayer player) {
        for (InstalledUpgrade upgrade : bag.installedUpgrades()) {
            if (!upgrade.kind().family().equals("pickup") || !UpgradeFilters.enabled(bag, upgrade) || !UpgradeFilters.matches(bag, upgrade, item.getItem())) continue;
            collect(bag, item, player);
            if (!item.isAlive() || item.getItem().isEmpty()) return true;
        }
        return false;
    }

    private static boolean canCollect(ItemEntity item, LivingEntity carrier, boolean remote) {
        if (!item.isAlive() || item.hasPickUpDelay() || item.getItem().isEmpty()) return false;
        if (!(item instanceof UpgradeAccess.ItemClaims claims)) return false;
        if (claims.fabricatedBackpacks$target() != null && (carrier == null || !claims.fabricatedBackpacks$target().equals(carrier.getUUID()))) return false;
        if (!remote) return true;
        CompoundTag data = item.getItem().getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (data.getBooleanOr("prevent_remote_movement", false) || data.getBooleanOr("no_magnet", false)) return false;
        if (item.entityTags().contains("fabricated_backpacks:no_magnet")) return false;
        return MOVEMENT_BLOCKERS.stream().noneMatch(blocker -> blocker.test(item, carrier));
    }

    private static boolean collect(BagInventory bag, ItemEntity item, LivingEntity carrier) {
        ItemStack original = item.getItem();
        ItemStack remainder = insert(bag, original, false);
        int moved = original.getCount() - remainder.getCount();
        if (moved <= 0) return false;
        if (carrier instanceof ServerPlayer player) {
            player.take(item, moved);
            player.awardStat(Stats.ITEM_PICKED_UP.get(original.getItem()), moved);
            player.onItemPickup(item);
        }
        if (remainder.isEmpty()) item.discard(); else item.setItem(remainder);
        item.level().playSound(null, item.getX(), item.getY(), item.getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, .2F, 1);
        return true;
    }

    private static void magnet(BagInventory bag, InstalledUpgrade upgrade, ServerLevel level, BlockPos position, LivingEntity carrier) {
        CompoundTag state = bag.settings(upgrade);
        if (!state.getBooleanOr("magnet_items", true) || level.getGameTime() < state.getLongOr("magnet_next", 0)) return;
        var rules = BackpackConfig.get().upgrades().magnet();
        int range = rules.radius(upgrade.kind());
        boolean moved = false;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(position).inflate(range), candidate -> canCollect(candidate, carrier, true))) {
            if (UpgradeFilters.matches(bag, upgrade, item.getItem())) moved |= collect(bag, item, carrier);
        }
        int delay = moved ? rules.activeTicks() : rules.idleTicks();
        bag.updateSettings(upgrade, tag -> tag.putLong("magnet_next", level.getGameTime() + delay));
    }

    public static int transfer(BagInventory bag, Container other, boolean deposit) {
        int moved = 0;
        for (var node : BackpackTraversal.upgradeBags(bag)) {
            if (!node.attached()) continue;
            int changed = TransferRuntime.transfer(node.inventory(), other, deposit);
            if (changed > 0) node.persist();
            moved += changed;
        }
        return moved;
    }
    private static boolean useFirst(BagInventory bag, java.util.function.Predicate<BagInventory> action) {
        for (var node : BackpackTraversal.upgradeBags(bag)) {
            if (node.attached() && action.test(node.inventory())) { node.persist(); return true; }
        }
        return false;
    }
    public static boolean blockAttack(BagInventory bag, ServerPlayer player, BlockState state, boolean manual) { return useFirst(bag, active -> ToolRuntime.forBlock(active, player, state, manual)); }
    public static boolean entityAttack(BagInventory bag, ServerPlayer player, LivingEntity target, boolean manual) { return useFirst(bag, active -> ToolRuntime.forEntity(active, player, target, manual)); }
    public static boolean pickBlock(BagInventory bag, ServerPlayer player, ItemStack desired) { return useFirst(bag, active -> TransferRuntime.pickBlock(active, player, desired)); }

    public static void outputTaken(BagInventory bag, int upgradeSlot, ServerPlayer player) {
        bag.installedUpgrades().stream().filter(upgrade -> upgrade.slot() == upgradeSlot && upgrade.kind().family().equals("cooking"))
                .findFirst().ifPresent(upgrade -> CookingRuntime.claimExperience(bag, upgrade, player));
    }

    public static boolean isValidAuxiliary(BagInventory bag, InstalledUpgrade upgrade, int slot, ItemStack stack, ServerLevel level) {
        if (slot < 0 || slot >= bag.inventorySlots(upgrade) || stack.isEmpty()) return false;
        return switch (upgrade.kind().family()) {
            case "jukebox" -> JukeboxRuntime.isDisc(stack);
            case "cooking" -> switch (slot) {
                case CookingRuntime.INPUT -> CookingRuntime.recipe(level, upgrade.kind(), stack).isPresent();
                case CookingRuntime.FUEL -> level.fuelValues().isFuel(stack) || stack.is(Items.BUCKET);
                default -> false;
            };
            case "tank", "battery" -> com.kadamitas.fabricatedbackpacks.resource.ResourceRuntime.isValidAuxiliary(upgrade.kind(), slot, stack);
            default -> true;
        };
    }

    public static boolean action(BagInventory bag, int upgradeSlot, String action, ServerPlayer player) {
        if (action == null || action.length() > 160) return false;
        InstalledUpgrade upgrade = bag.installedUpgrades().stream().filter(candidate -> candidate.slot() == upgradeSlot).findFirst().orElse(null);
        if (upgrade == null) return false;
        CompoundTag state = bag.settings(upgrade);
        if (action.equals("toggle")) {
            if (FIXED_UPGRADES.contains(upgrade.kind().family())) return false;
            boolean enabled = !state.getBooleanOr("enabled", true);
            bag.updateSettings(upgrade, tag -> { tag.putBoolean("enabled", enabled); if (!enabled) tag.putBoolean("burning", false); });
            if (!enabled) {
                JukeboxRuntime.stopUpgrade(bag, upgradeSlot, player.level().getServer());
                AlchemyRuntime.cancel(bag, upgradeSlot, player.level().getServer());
            }
            return true;
        }
        if (upgrade.kind().family().equals("jukebox") && Set.of("play", "stop", "next", "previous", "prev", "shuffle", "repeat").contains(action)) {
            if (!state.getBooleanOr("enabled", true) && !action.equals("stop")) return false;
            Context context = CONTEXTS.get(new Key(player.level().getServer(), bag.identity()));
            return JukeboxRuntime.action(bag, upgrade, player.level(), context == null ? player.blockPosition() : context.position,
                    context == null ? player : context.carrier, action);
        }
        if (action.equals("claim_xp") && upgrade.kind().family().equals("cooking")) { outputTaken(bag, upgradeSlot, player); return true; }
        String prefix = action.startsWith("input_") ? "input_" : action.startsWith("fuel_") ? "fuel_" : "";
        String operation = action.substring(prefix.length());
        if (!prefix.isEmpty() && !CookingRuntime.automatic(upgrade.kind())) return false;
        if (operation.equals("filter_mode") && bag.filterSlots(upgrade) > 0) {
            cycle(bag, upgrade, prefix + "filter_mode", prefix.isEmpty() && !upgrade.kind().family().equals("void") ? "BLOCK" : "ALLOW", "ALLOW", "BLOCK", "CONTENTS");
            if (bag.settings(upgrade).getStringOr(prefix + "filter_mode", "BLOCK").equals("CONTENTS")
                    && bag.settings(upgrade).getStringOr(prefix + "filter_match", "ITEM").equals("TAGS")) {
                bag.updateSettings(upgrade, tag -> tag.putString(prefix + "filter_match", "ITEM"));
            }
            return true;
        }
        if (operation.equals("filter_match") && (upgrade.kind().advanced() || prefix.equals("input_"))) {
            cycle(bag, upgrade, prefix + "filter_match", "ITEM", "ITEM", "NAMESPACE", "TAGS");
            if (bag.settings(upgrade).getStringOr(prefix + "filter_match", "ITEM").equals("TAGS") && bag.settings(upgrade).getStringOr(prefix + "filter_mode", "BLOCK").equals("CONTENTS")) {
                bag.updateSettings(upgrade, tag -> tag.putString(prefix + "filter_mode", "ALLOW"));
            }
            return true;
        }
        if (Set.of("match_damage", "match_components", "tag_match").contains(operation) && (upgrade.kind().advanced() || prefix.equals("input_"))) {
            if (operation.equals("tag_match")) cycle(bag, upgrade, prefix + operation, "ANY", "ANY", "ALL");
            else toggle(bag, upgrade, prefix + operation, false);
            return true;
        }
        if (operation.startsWith("tag:") && (upgrade.kind().advanced() || prefix.equals("input_"))) {
            String tag = operation.substring(4);
            if (Identifier.tryParse(tag) == null || !tag.contains(":")) return false;
            Set<String> selected = new HashSet<>(Arrays.asList(state.getStringOr(prefix + "tags", "").split(",")));
            selected.remove("");
            if (!selected.add(tag)) selected.remove(tag);
            if (selected.size() > 64) return false;
            bag.updateSettings(upgrade, data -> data.putString(prefix + "tags", String.join(",", selected.stream().sorted().toList())));
            return true;
        }
        switch (action) {
            case "external_output" -> { if (!upgrade.kind().family().equals("battery")) return false; toggle(bag, upgrade, action, true); }
            case "result_destination" -> { if (!Set.of("crafting", "stonecutter", "anvil", "smithing").contains(upgrade.kind().family())) return false; cycle(bag, upgrade, action, "STORAGE", "STORAGE", "PLAYER"); }
            case "grid_refill" -> { if (!Set.of("crafting", "stonecutter").contains(upgrade.kind().family())) return false; toggle(bag, upgrade, action, false); }
            case "filter_direction" -> { if (!upgrade.kind().family().equals("filter")) return false; cycle(bag, upgrade, action, "BOTH", "BOTH", "INPUT", "OUTPUT"); }
            case "void_mode" -> { if (!upgrade.kind().family().equals("void")) return false; cycle(bag, upgrade, action, "STORAGE_OVERFLOW", alwaysVoidAllowed ? new String[] {"STORAGE_OVERFLOW", "SLOT_OVERFLOW", "ALWAYS"} : new String[] {"STORAGE_OVERFLOW", "SLOT_OVERFLOW"}); }
            case "work_in_gui" -> { if (!Set.of("void", "compacting").contains(upgrade.kind().family())) return false; toggle(bag, upgrade, action, false); }
            case "hunger_mode" -> { if (!upgrade.kind().advanced() || !upgrade.kind().family().equals("feeding")) return false; cycle(bag, upgrade, action, "HALF", "HALF", "FULL", "ANY"); }
            case "feed_when_hurt" -> { if (!upgrade.kind().family().equals("feeding")) return false; toggle(bag, upgrade, action, true); }
            case "magnet_items", "magnet_xp" -> { if (!upgrade.kind().family().equals("magnet")) return false; toggle(bag, upgrade, action, true); }
            case "tool_mode" -> { if (!upgrade.kind().advanced() || !upgrade.kind().family().equals("tool_swapper")) return false; cycle(bag, upgrade, action, "AUTO", "AUTO", "ONLY_TOOLS", "MANUAL"); }
            case "swap_weapons" -> { if (!upgrade.kind().advanced() || !upgrade.kind().family().equals("tool_swapper")) return false; toggle(bag, upgrade, action, true); }
            case "alchemy_targets" -> { if (!upgrade.kind().advanced() || !upgrade.kind().family().equals("alchemy")) return false; cycle(bag, upgrade, action, "BOTH", "BOTH", "PLAYERS", "NONPLAYERS"); }
            case "alchemy_match_duration", "alchemy_match_amplifier", "alchemy_match_all", "alchemy_all_missing" -> {
                if (!upgrade.kind().advanced() || !upgrade.kind().family().equals("alchemy")) return false;
                toggle(bag, upgrade, action, true);
            }
            default -> { return rowAction(bag, upgrade, action); }
        }
        return true;
    }

    private static boolean rowAction(BagInventory bag, InstalledUpgrade upgrade, String action) {
        String[] parts = action.split(":");
        if (parts.length < 2 || parts.length > 3) return false;
        int row;
        try { row = Integer.parseInt(parts[1]); } catch (NumberFormatException invalid) { return false; }
        if (row < 0 || row >= bag.filterSlots(upgrade)) return false;
        if (parts[0].equals("refill_target") && upgrade.kind().family().equals("refill") && upgrade.kind().advanced()) {
            cycle(bag, upgrade, "refill_target_" + row, "ANY", "ANY", "MAIN_HAND", "OFF_HAND", "HOTBAR_1", "HOTBAR_2", "HOTBAR_3", "HOTBAR_4", "HOTBAR_5", "HOTBAR_6", "HOTBAR_7", "HOTBAR_8", "HOTBAR_9");
            return true;
        }
        if (!upgrade.kind().family().equals("alchemy")) return false;
        if (parts[0].equals("alchemy_condition")) {
            if (bag.ghost(upgrade, row).isEmpty()) return false;
            cycle(bag, upgrade, "alchemy_condition_" + row, AlchemyRuntime.defaultCondition(bag.ghost(upgrade, row)).name(),
                    Arrays.stream(AlchemyRuntime.Condition.values()).map(Enum::name).toArray(String[]::new));
            return true;
        }
        if (parts[0].equals("alchemy_health") && parts.length == 3) {
            int change;
            try { change = Integer.parseInt(parts[2]); } catch (NumberFormatException invalid) { return false; }
            if (change != 5 && change != -5) return false;
            int health = Math.clamp(bag.settings(upgrade).getIntOr("alchemy_health_" + row, 75) + change, 0, 100);
            bag.updateSettings(upgrade, tag -> tag.putInt("alchemy_health_" + row, health));
            return true;
        }
        return false;
    }

    private static void toggle(BagInventory bag, InstalledUpgrade upgrade, String key, boolean initial) {
        boolean next = !bag.settings(upgrade).getBooleanOr(key, initial);
        bag.updateSettings(upgrade, tag -> tag.putBoolean(key, next));
    }
    private static void cycle(BagInventory bag, InstalledUpgrade upgrade, String key, String initial, String... values) {
        int index = Arrays.asList(values).indexOf(bag.settings(upgrade).getStringOr(key, initial));
        String next = values[(index + 1) % values.length];
        bag.updateSettings(upgrade, tag -> tag.putString(key, next));
    }

    public static void stopUpgrade(BagInventory bag, int slot, MinecraftServer server) {
        JukeboxRuntime.stopUpgrade(bag, slot, server);
        AlchemyRuntime.cancel(bag, slot, server);
    }

    public static void endServerTick(MinecraftServer server) {
        JukeboxRuntime.endServerTick(server);
        AlchemyRuntime.endServerTick(server);
        if (server.getTickCount() % 40 == 0) CONTEXTS.entrySet().removeIf(entry -> entry.getKey().server() == server && server.getTickCount() - entry.getValue().lastSeen > 40);
    }
    public static void stopAll(MinecraftServer server) {
        JukeboxRuntime.stopAll(server);
        AlchemyRuntime.stopAll(server);
        CONTEXTS.keySet().removeIf(key -> key.server() == server);
        INSERTED.clear();
        MANUAL_CHANGES.clear();
    }
}
