package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import java.util.ArrayList;
import java.util.List;

final class UpgradeControls {
    private UpgradeControls() {}
    static String title(UpgradeKind kind) {
        return switch (kind) {
            case SMELTING -> "Smelt";
            case AUTO_SMELTING -> "Auto-smelt";
            case SMOKING -> "Smoke";
            case AUTO_SMOKING -> "Auto-smoke";
            case BLASTING -> "Blast";
            case AUTO_BLASTING -> "Auto-blast";
            default -> switch (kind.family()) {
                case "tool_swapper" -> "Tool swap";
                case "mob_catcher" -> "Mob catcher";
                case "xp_pump" -> "XP pump";
                case "stack" -> "Stack size";
                default -> Character.toUpperCase(kind.family().charAt(0)) + kind.family().substring(1);
            };
        };
    }
    static List<String> actions(BagInventory bag, InstalledUpgrade upgrade) {
        UpgradeKind kind = upgrade.kind();
        List<String> actions = new ArrayList<>();
        if (kind.family().equals("jukebox")) actions.addAll(kind.advanced() ? List.of("play", "stop", "previous", "next", "shuffle", "repeat") : List.of("play", "stop"));
        else if (!List.of("stack", "tank", "battery", "crafting", "stonecutter", "anvil", "smithing", "infinity", "inception", "mob_catcher", "everlasting").contains(kind.family())) actions.add("toggle");
        if (bag.filterSlots(upgrade) > 0 && !kind.family().equals("cooking")) {
            actions.add("filter_mode");
            if (kind.advanced()) actions.addAll(List.of("filter_match", "match_damage", "match_components", "tag_match", "tags"));
        }
        actions.addAll(switch (kind.family()) {
            case "filter" -> List.of("filter_direction");
            case "inception" -> List.of("inception_outer_inventory", "inception_inner_upgrades", "inception_nested_first");
            case "magnet" -> List.of("magnet_items", "magnet_xp");
            case "feeding" -> kind.advanced() ? List.of("hunger_mode", "feed_when_hurt") : List.of();
            case "void" -> List.of("void_mode", "work_in_gui", "fluids");
            case "compacting" -> List.of("compact_anything", "work_in_gui");
            case "cooking" -> bag.filterSlots(upgrade) > 0 ? List.of("input_filter_mode", "input_filter_match", "input_match_damage", "input_match_components", "input_tag_match", "input_tags", "fuel_filter_mode") : List.of();
            case "tool_swapper" -> kind.advanced() ? List.of("tool_mode", "swap_weapons") : List.of();
            case "alchemy" -> kind.advanced() ? List.of("alchemy_targets", "alchemy_match_duration", "alchemy_match_amplifier", "alchemy_match_all", "alchemy_all_missing") : List.of();
            case "tank", "battery" -> List.of("container");
            case "pump" -> kind.advanced() ? List.of("direction", "handlers", "hands", "world") : List.of("direction", "handlers");
            case "xp_pump" -> List.of("direction", "store", "take", "store_all", "take_all", "target_up", "target_down", "levels_up", "levels_down", "mending");
            default -> List.of();
        });
        if (kind.family().equals("alchemy") || kind == UpgradeKind.ADVANCED_REFILL) actions.add("slot_rules");
        return List.copyOf(actions);
    }
    static String label(String action) {
        if (action.equals("toggle")) return "Enabled";
        if (action.equals("container")) return "Use cursor container";
        if (action.equals("fluids")) return "Fluid filters";
        if (action.equals("slot_rules")) return "Slot rules";
        String words = action.replace('_', ' ').replace(':', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
