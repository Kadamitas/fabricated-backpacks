package com.kadamitas.fabricatedbackpacks.upgrade;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Swaps owned stacks using complete backpack/player plans, never overwriting a held item. */
public final class ToolRuntime {
    private record Score(int priority, boolean preferred, double value) implements Comparable<Score> {
        private static final Score NONE = new Score(-1, false, 0);
        @Override public int compareTo(Score other) {
            int order = Integer.compare(priority, other.priority);
            if (order == 0) order = Boolean.compare(preferred, other.preferred);
            return order == 0 ? Double.compare(value, other.value) : order;
        }
    }
    private record Candidate(int slot, Score score) { }
    private ToolRuntime() { }

    public static boolean forBlock(BagInventory bag, ServerPlayer player, BlockState state, boolean manual) {
        InstalledUpgrade upgrade = installed(bag, manual);
        if (upgrade == null || BackpackRegistry.isBackpack(player.getMainHandItem())) return false;
        if (!permits(bag, upgrade, player, manual)) return false;
        List<Candidate> candidates = new ArrayList<>();
        Container storage = BackpackTraversal.processingInventory(bag, player);
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            ItemStack item = storage.getItem(slot);
            if (item.isEmpty() || BackpackRegistry.isBackpack(item) || !storage.canTakeItem(player.getInventory(), slot, item) || !allowed(bag, upgrade, item)) continue;
            Score score = blockScore(player.level().getServer(), item, state, manual);
            if (score != Score.NONE) candidates.add(new Candidate(slot, score));
        }
        return choose(bag, player, candidates, blockScore(player.level().getServer(), player.getMainHandItem(), state, manual), manual);
    }

    public static boolean forEntity(BagInventory bag, ServerPlayer player, LivingEntity target, boolean manual) {
        InstalledUpgrade upgrade = installed(bag, manual);
        if (upgrade == null || BackpackRegistry.isBackpack(player.getMainHandItem())
                || (upgrade.kind().advanced() && !NbtAccess.getBooleanOr(bag.settings(upgrade), "swap_weapons", true))) return false;
        if (!permits(bag, upgrade, player, manual) || !manual && !heldTool(player)) return false;
        List<Candidate> candidates = new ArrayList<>();
        Container storage = BackpackTraversal.processingInventory(bag, player);
        for (int slot = 0; slot < storage.getContainerSize(); slot++) {
            ItemStack item = storage.getItem(slot);
            if (item.isEmpty() || BackpackRegistry.isBackpack(item) || !storage.canTakeItem(player.getInventory(), slot, item) || !allowed(bag, upgrade, item)) continue;
            Score score = weaponScore(player, target, item, manual);
            if (score != Score.NONE) candidates.add(new Candidate(slot, score));
        }
        return choose(bag, player, candidates, weaponScore(player, target, player.getMainHandItem(), manual), manual);
    }

    private static InstalledUpgrade installed(BagInventory bag, boolean manual) {
        return bag.installedUpgrades().stream().filter(upgrade -> upgrade.kind().family().equals("tool_swapper")
                && (!manual || upgrade.kind().advanced()) && UpgradeFilters.enabled(bag, upgrade)).findFirst().orElse(null);
    }

    private static boolean allowed(BagInventory bag, InstalledUpgrade upgrade, ItemStack item) {
        return !upgrade.kind().advanced() || UpgradeFilters.matches(bag, upgrade, item);
    }

    private static boolean permits(BagInventory bag, InstalledUpgrade upgrade, ServerPlayer player, boolean manual) {
        if (manual || !upgrade.kind().advanced()) return true;
        return switch (NbtAccess.getStringOr(bag.settings(upgrade), "tool_mode", "AUTO")) {
            case "MANUAL" -> false;
            case "ONLY_TOOLS" -> heldTool(player);
            default -> true;
        };
    }

    private static boolean heldTool(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        return !held.isEmpty() && !held.is(ItemTags.SWORDS)
                && (held.has(DataComponents.TOOL) || ToolRules.recognizes(player.level().getServer(), held));
    }

    private static Score blockScore(MinecraftServer server, ItemStack item, BlockState state, boolean manual) {
        if (item.isEmpty()) return Score.NONE;
        ToolRule rule = ToolRules.forBlock(server, state, item, manual);
        boolean correct = item.isCorrectToolForDrops(state);
        if (state.requiresCorrectToolForDrops() && !correct && (rule == null || rule.requireCorrectTool())) return Score.NONE;
        double speed = item.getDestroySpeed(state);
        if (!Double.isFinite(speed) || speed <= 1 && !correct && rule == null) return Score.NONE;
        return new Score(rule == null ? 0 : rule.priority() + 1, correct, speed);
    }

    private static Score weaponScore(ServerPlayer player, LivingEntity target, ItemStack item, boolean manual) {
        if (item.isEmpty()) return Score.NONE;
        ToolRule rule = ToolRules.forEntity(player.level().getServer(), target, item, manual);
        double[] modifiers = { 0, 0, 1 };
        item.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(Attributes.ATTACK_DAMAGE)) switch (modifier.operation()) {
                case ADD_VALUE -> modifiers[0] += modifier.amount();
                case ADD_MULTIPLIED_BASE -> modifiers[1] += modifier.amount();
                case ADD_MULTIPLIED_TOTAL -> modifiers[2] *= 1 + modifier.amount();
            }
        });
        double base = player.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
        double damage = (base + modifiers[0]) * (1 + modifiers[1]) * modifiers[2];
        boolean sword = item.is(ItemTags.SWORDS);
        if (!Double.isFinite(damage) || damage <= base && !sword && rule == null) return Score.NONE;
        return new Score(rule == null ? 0 : rule.priority() + 1, sword, damage);
    }

    private static boolean choose(BagInventory bag, ServerPlayer player, List<Candidate> candidates, Score heldScore, boolean manual) {
        candidates.sort(Comparator.comparing(Candidate::score).reversed().thenComparingInt(Candidate::slot));
        if (manual && !candidates.isEmpty()) {
            int previous = NbtAccess.getIntOr(bag.settings(), "last_tool_slot", -1);
            int selected = 0;
            for (int index = 0; index < candidates.size(); index++) if (candidates.get(index).slot() == previous) selected = (index + 1) % candidates.size();
            Candidate candidate = candidates.get(selected);
            if (swapToHand(bag, player, candidate.slot(), 1)) {
                bag.updateSettings(tag -> tag.putInt("last_tool_slot", candidate.slot()));
                return true;
            }
            return false;
        }
        for (Candidate candidate : candidates) {
            if (candidate.score().compareTo(heldScore) <= 0) break;
            if (swapToHand(bag, player, candidate.slot(), 1)) return true;
        }
        return false;
    }

    public static boolean swapToHand(BagInventory bag, ServerPlayer player, int sourceSlot, int count) {
        Container storage = BackpackTraversal.processingInventory(bag, player);
        if (sourceSlot < 0 || sourceSlot >= storage.getContainerSize() || count <= 0) return false;
        ItemStack source = storage.getItem(sourceSlot);
        ItemStack held = player.getMainHandItem();
        if (source.isEmpty() || source.getCount() < count || count > source.getMaxStackSize()
                || BackpackRegistry.isBackpack(held) || ItemStack.isSameItemSameComponents(source, held)
                || !storage.canTakeItem(player.getInventory(), sourceSlot, source)) return false;
        List<ItemStack> bagPlan = InventoryMoves.snapshot(storage);
        List<ItemStack> playerPlan = InventoryMoves.snapshot(player.getInventory());
        bagPlan.set(sourceSlot, source.copyWithCount(source.getCount() - count));
        playerPlan.set(player.getInventory().selected, source.copyWithCount(count));
        ItemStack remaining = InventoryMoves.insertIntoPlan(storage, bagPlan, held, false);
        if (!InventoryMoves.insertIntoPlan(player.getInventory(), playerPlan, remaining, false).isEmpty()) return false;
        // Publish the player plan before modifying its bag carrier's component
        // snapshot, so an unchanged carrier keeps its physical identity.
        InventoryMoves.commit(player.getInventory(), playerPlan);
        InventoryMoves.commit(storage, bagPlan);
        return true;
    }
}
