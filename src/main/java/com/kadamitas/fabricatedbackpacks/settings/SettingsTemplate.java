package com.kadamitas.fabricatedbackpacks.settings;

import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.resource.ResourceComponents;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.CustomData;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** Settings-only snapshots cannot contain physical items, fluid, energy, experience or captured mobs. */
public record SettingsTemplate(CustomData main, InventorySnapshot memory, List<Upgrade> upgrades) {
    public static final Set<String> MAIN_KEYS = Set.of("memory_components", "no_sort", "no_sort_color", "display_slot", "display_rotation",
            "display_depth", "keep_tab", "keep_search", "shift_into_tab", "share_access", "sort_order", "inception_nested_first",
            "inception_inner_upgrades", "inception_outer_inventory");
    private static final Set<String> UPGRADE_KEYS = Set.of("enabled", "filter_mode", "filter_match", "match_damage", "match_components",
            "tag_match", "tags", "filter_direction", "void_mode", "work_in_gui", "hunger_mode", "feed_when_hurt",
            "magnet_items", "magnet_xp", "tool_mode", "swap_weapons", "alchemy_targets", "alchemy_match_duration", "alchemy_match_amplifier",
            "alchemy_match_all", "alchemy_all_missing", "input_filter_mode", "input_filter_match", "input_match_damage", "input_match_components",
            "input_tag_match", "input_tags", "fuel_filter_mode", "repeat", "shuffle", "direction", "handlers",
            "hands", "world", "mending", "target", "levels", "result_destination", "grid_refill");
    public static final Codec<SettingsTemplate> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CustomData.CODEC.fieldOf("main").forGetter(SettingsTemplate::main),
            InventorySnapshot.CODEC.fieldOf("memory").forGetter(SettingsTemplate::memory),
            Upgrade.CODEC.listOf(0, 10).fieldOf("upgrades").forGetter(SettingsTemplate::upgrades)).apply(instance, SettingsTemplate::new));

    public SettingsTemplate { upgrades = List.copyOf(upgrades); }

    public static SettingsTemplate capture(BagInventory bag) {
        return new SettingsTemplate(CustomData.of(select(bag.settings(), MAIN_KEYS)),
                bag.stack().getOrDefault(BagComponents.MEMORY, InventorySnapshot.EMPTY),
                bag.installedUpgrades().stream().map(upgrade -> {
                    CompoundTag settings = selectUpgrade(bag.settings(upgrade));
                    if (automaticCooking(upgrade.kind())) {
                        settings.putInt("cooking_input_filter_slots", bag.cookingInputFilters(upgrade));
                        settings.putInt("cooking_fuel_filter_slots", bag.cookingFuelFilters(upgrade));
                    }
                    return new Upgrade(upgrade.slot(), upgrade.kind(), CustomData.of(settings), captureFilters(bag, upgrade),
                            upgrade.kind().family().equals("void") ? upgrade.stack().getOrDefault(ResourceComponents.VOID_FLUID_FILTERS, List.of()) : List.of());
                }).toList());
    }

    private static boolean automaticCooking(UpgradeKind kind) { return kind.family().equals("cooking") && kind.filterSlots() > 0; }
    private static InventorySnapshot captureFilters(BagInventory bag, InstalledUpgrade upgrade) {
        var entries = new java.util.ArrayList<InventorySnapshot.Entry>();
        for (int slot = 0; slot < bag.filterSlots(upgrade); slot++) {
            ItemStack ghost = bag.ghost(upgrade, slot);
            if (!ghost.isEmpty()) entries.add(new InventorySnapshot.Entry(slot, ItemStackTemplate.fromNonEmptyStack(ghost.copyWithCount(1)), 1));
        }
        return new InventorySnapshot(bag.filterSlots(upgrade), entries);
    }

    public void apply(BagInventory bag) {
        CompoundTag safe = select(main.copyTag(), MAIN_KEYS);
        int[] excluded = Arrays.stream(safe.getIntArray("no_sort").orElseGet(() -> new int[0]))
                .filter(slot -> slot >= 0 && slot < bag.getContainerSize()).distinct().sorted().toArray();
        safe.putIntArray("no_sort", excluded);
        int display = safe.getIntOr("display_slot", -1);
        safe.putInt("display_slot", display >= 0 && display < bag.getContainerSize() ? display : -1);
        safe.putInt("display_rotation", Math.floorMod(safe.getIntOr("display_rotation", 0), 360) / 45 * 45);
        safe.putInt("display_depth", Math.clamp(safe.getIntOr("display_depth", 0), -16, 16));
        bag.updateSettings(tag -> { MAIN_KEYS.forEach(tag::remove); safe.entrySet().forEach(entry -> tag.put(entry.getKey(), entry.getValue().copy())); });
        var memoryEntries = memory.entries().stream().filter(entry -> entry.slot() < bag.getContainerSize()
                && (bag.getItem(entry.slot()).isEmpty() || ItemStack.isSameItemSameComponents(bag.getItem(entry.slot()), entry.create())))
                .map(entry -> new InventorySnapshot.Entry(entry.slot(), entry.item().withCount(1), 1)).toList();
        bag.stack().set(BagComponents.MEMORY, new InventorySnapshot(bag.getContainerSize(), memoryEntries));
        for (Upgrade saved : upgrades) bag.installedUpgrades().stream().filter(installed -> installed.slot() == saved.slot()
                && installed.kind().family().equals(saved.kind().family())).findFirst().ifPresent(installed -> {
            int targetInputs = bag.cookingInputFilters(installed), targetFuel = bag.cookingFuelFilters(installed);
            int sourceInputs = Math.clamp(saved.settings().copyTag().getIntOr("cooking_input_filter_slots", 8), 1, 32);
            int sourceFuel = Math.clamp(saved.settings().copyTag().getIntOr("cooking_fuel_filter_slots", 4), 1, 32);
            CompoundTag settings = selectUpgrade(saved.settings().copyTag());
            bag.updateSettings(installed, tag -> {
                selectUpgrade(tag).keySet().forEach(tag::remove);
                settings.entrySet().forEach(entry -> tag.put(entry.getKey(), entry.getValue().copy()));
            });
            boolean cooking = automaticCooking(installed.kind());
            var filters = saved.filters().entries().stream().filter(entry -> !cooking
                            ? entry.slot() < bag.filterSlots(installed)
                            : entry.slot() < sourceInputs ? entry.slot() < targetInputs
                            : entry.slot() - sourceInputs < Math.min(sourceFuel, targetFuel))
                    .map(entry -> new InventorySnapshot.Entry(cooking && entry.slot() >= sourceInputs
                            ? entry.slot() - sourceInputs + targetInputs : entry.slot(), entry.item().withCount(1), 1)).toList();
            installed.stack().set(BagComponents.FILTERS, new InventorySnapshot(bag.filterSlots(installed), filters));
            if (cooking) bag.updateSettings(installed, tag -> {
                tag.putInt("cooking_input_filter_slots", targetInputs);
                tag.putInt("cooking_fuel_filter_slots", targetFuel);
            });
            if (installed.kind().family().equals("void")) {
                var fluidFilters = saved.fluidFilters().stream().limit(bag.filterSlots(installed)).toList();
                if (fluidFilters.isEmpty()) installed.stack().remove(ResourceComponents.VOID_FLUID_FILTERS);
                else installed.stack().set(ResourceComponents.VOID_FLUID_FILTERS, fluidFilters);
            }
        });
        bag.save();
    }

    static CompoundTag select(CompoundTag source, Set<String> keys) {
        CompoundTag selected = new CompoundTag();
        keys.forEach(key -> { if (source.contains(key)) selected.put(key, source.get(key).copy()); });
        return selected;
    }
    private static CompoundTag selectUpgrade(CompoundTag source) {
        CompoundTag result = select(source, UPGRADE_KEYS);
        source.entrySet().stream().filter(entry -> entry.getKey().matches("(refill_target|alchemy_condition|alchemy_health)_[0-9]{1,2}"))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue().copy()));
        return result;
    }
    public record Upgrade(int slot, UpgradeKind kind, CustomData settings, InventorySnapshot filters, List<FluidVariant> fluidFilters) {
        public Upgrade { fluidFilters = List.copyOf(fluidFilters); }
        public Upgrade(int slot, UpgradeKind kind, CustomData settings, InventorySnapshot filters) {
            this(slot, kind, settings, filters, List.of());
        }
        private static final Codec<UpgradeKind> KIND = Codec.STRING.flatXmap(id -> UpgradeKind.byId(id).map(DataResult::success)
                .orElseGet(() -> DataResult.error(() -> "Unknown upgrade " + id)), kind -> DataResult.success(kind.id()));
        public static final Codec<Upgrade> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.intRange(0, 9).fieldOf("slot").forGetter(Upgrade::slot), KIND.fieldOf("kind").forGetter(Upgrade::kind),
                CustomData.CODEC.fieldOf("settings").forGetter(Upgrade::settings),
                InventorySnapshot.CODEC.fieldOf("filters").forGetter(Upgrade::filters),
                FluidVariant.CODEC.listOf(0, 64).optionalFieldOf("fluid_filters", List.of()).forGetter(Upgrade::fluidFilters)).apply(instance, Upgrade::new));
    }
}
