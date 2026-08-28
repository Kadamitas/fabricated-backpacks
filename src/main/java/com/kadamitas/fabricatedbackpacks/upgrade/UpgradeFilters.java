package com.kadamitas.fabricatedbackpacks.upgrade;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.gameplay.BackpackTraversal;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Runtime adapter for ghost filters. Component equality is typed, independent of durability. */
public final class UpgradeFilters {
    private UpgradeFilters() { }

    public static boolean enabled(BagInventory bag, InstalledUpgrade upgrade) {
        return NbtAccess.getBooleanOr(bag.settings(upgrade), "enabled", true);
    }

    public static boolean matches(BagInventory bag, InstalledUpgrade upgrade, ItemStack candidate) {
        return matches(bag, upgrade, candidate, "", 0, bag.filterSlots(upgrade), false, null);
    }

    public static boolean matches(BagInventory bag, InstalledUpgrade upgrade, ItemStack candidate, String prefix,
                                  int first, int count, boolean emptyAllowAny, Container contentsOverride) {
        if (candidate.isEmpty()) return false;
        CompoundTag settings = bag.settings(upgrade);
        String defaultMode = upgrade.kind().family().equals("void") || !prefix.isEmpty() ? "ALLOW" : "BLOCK";
        String mode = NbtAccess.getStringOr(settings, prefix + "filter_mode", defaultMode);
        String match = NbtAccess.getStringOr(settings, prefix + "filter_match", "ITEM");
        boolean advanced = upgrade.kind().advanced() || (upgrade.kind().family().equals("cooking") && prefix.equals("input_"));
        if (!advanced) match = "ITEM";
        boolean damage = advanced && NbtAccess.getBooleanOr(settings, prefix + "match_damage", false);
        boolean components = advanced && NbtAccess.getBooleanOr(settings, prefix + "match_components", false);
        List<ItemStack> entries = new ArrayList<>();
        for (int slot = first; slot < first + count; slot++) {
            ItemStack ghost = bag.ghost(upgrade, slot);
            if (!ghost.isEmpty()) entries.add(ghost);
        }
        if (mode.equals("CONTENTS")) {
            List<ItemStack> contents = new ArrayList<>();
            Container storage = contentsOverride == null ? BackpackTraversal.processingInventory(bag) : contentsOverride;
            for (int slot = 0; slot < storage.getContainerSize(); slot++) {
                ItemStack stack = storage.getItem(slot);
                if (!stack.isEmpty()) contents.add(stack);
            }
            if (contentsOverride == null) for (var node : BackpackTraversal.inventoryBags(bag)) contents.addAll(node.inventory().memoryItems());
            String primary = match.equals("TAGS") ? "ITEM" : match;
            return contents.stream().anyMatch(item -> same(candidate, item, primary, damage, components));
        }
        boolean selected;
        if (match.equals("TAGS")) {
            Set<String> selectedTags = Arrays.stream(NbtAccess.getStringOr(settings, prefix + "tags", "").split(","))
                    .map(String::trim).filter(text -> !text.isEmpty()).collect(Collectors.toSet());
            Set<String> tags = candidate.getTags().map(tag -> tag.location().toString()).collect(Collectors.toSet());
            selected = NbtAccess.getStringOr(settings, prefix + "tag_match", "ANY").equals("ALL")
                    ? tags.containsAll(selectedTags) : selectedTags.stream().anyMatch(tags::contains);
            if (damage || components) selected &= entries.stream().anyMatch(item -> secondary(candidate, item, damage, components));
        } else {
            String primary = match;
            selected = entries.stream().anyMatch(item -> same(candidate, item, primary, damage, components));
            if (entries.isEmpty() && mode.equals("ALLOW") && emptyAllowAny) selected = true;
        }
        return mode.equals("BLOCK") ? !selected : selected;
    }

    public static boolean same(ItemStack first, ItemStack second, String primary, boolean damage, boolean components) {
        if (first.isEmpty() || second.isEmpty()) return false;
        boolean identity = primary.equals("NAMESPACE")
                ? BuiltInRegistries.ITEM.getKey(first.getItem()).getNamespace().equals(BuiltInRegistries.ITEM.getKey(second.getItem()).getNamespace())
                : ItemStack.isSameItem(first, second);
        return identity && secondary(first, second, damage, components);
    }

    private static boolean secondary(ItemStack first, ItemStack second, boolean damage, boolean components) {
        if (damage && first.getDamageValue() != second.getDamageValue()) return false;
        if (!components) return true;
        // Comparing TypedDataComponents avoids lossy toString-based representations of NBT or other values.
        Set<Object> left = new HashSet<>();
        Set<Object> right = new HashSet<>();
        first.getComponents().forEach(component -> { if (component.type() != DataComponents.DAMAGE) left.add(component); });
        second.getComponents().forEach(component -> { if (component.type() != DataComponents.DAMAGE) right.add(component); });
        return left.equals(right);
    }
}
