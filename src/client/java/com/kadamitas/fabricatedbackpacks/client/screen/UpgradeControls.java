package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.compat.NbtAccess;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackIconButton.Icon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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
        if (kind.family().equals("cooking") && bag.filterSlots(upgrade) > 0)
            return List.of("input_filter_mode", "input_filter_match", "input_match_damage", "input_match_components",
                    "toggle", "input_tag_match", "input_tags", "fuel_filter_mode");
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
            case "tool_swapper" -> kind.advanced() ? List.of("tool_mode", "swap_weapons") : List.of();
            case "alchemy" -> kind.advanced() ? List.of("alchemy_targets", "alchemy_match_duration", "alchemy_match_amplifier", "alchemy_match_all", "alchemy_all_missing") : List.of();
            case "tank" -> List.of("container");
            case "battery" -> List.of("container", "external_output");
            case "pump" -> kind.advanced() ? List.of("direction", "handlers", "hands", "world") : List.of("direction", "handlers");
            case "xp_pump" -> List.of("direction", "store", "take", "store_all", "take_all", "target_up", "target_down", "levels_up", "levels_down", "mending");
            default -> List.of();
        });
        if (kind.family().equals("alchemy") || kind == UpgradeKind.ADVANCED_REFILL) actions.add("slot_rules");
        return List.copyOf(actions);
    }

    /** Paint and accessible text derive from the same effective setting, including an absent key's default. */
    record Presentation(Icon icon, String label, boolean selected) {}

    static Presentation presentation(String action, UpgradeKind kind, CompoundTag settings) {
        String key = action.equals("toggle") ? "enabled" : action;
        String operation = action.startsWith("input_") ? action.substring(6)
                : action.startsWith("fuel_") ? action.substring(5) : action;
        return switch (operation) {
            case "filter_mode" -> {
                String initial = !operation.equals(action) || kind.family().equals("void") ? "ALLOW" : "BLOCK";
                String value = NbtAccess.getStringOr(settings, key, initial);
                yield new Presentation(switch (value) {
                    case "BLOCK" -> Icon.FILTER_BLOCK;
                    case "CONTENTS" -> Icon.FILTER_CONTENTS;
                    default -> Icon.FILTER_ALLOW;
                }, label(action) + ": " + value, false);
            }
            case "filter_match" -> {
                String value = NbtAccess.getStringOr(settings, key, "ITEM");
                yield new Presentation(switch (value) {
                    case "NAMESPACE" -> Icon.MATCH_MOD;
                    case "TAGS" -> Icon.MATCH_TAGS;
                    default -> Icon.MATCH_ITEM;
                }, label(action) + ": " + value, false);
            }
            case "match_damage", "match_components" -> {
                boolean match = NbtAccess.getBooleanOr(settings, key, false);
                Icon icon = operation.equals("match_damage")
                        ? match ? Icon.MATCH_DAMAGE : Icon.IGNORE_DAMAGE
                        : match ? Icon.MATCH_COMPONENTS : Icon.IGNORE_COMPONENTS;
                yield new Presentation(icon, label(action) + (match ? ": On" : ": Off"), false);
            }
            case "tag_match" -> new Presentation(Icon.TAG, label(action) + ": " + NbtAccess.getStringOr(settings, key, "ANY"), false);
            default -> {
                Boolean initial = booleanDefault(key);
                boolean booleanOption = initial != null || settings.contains(key, Tag.TAG_ANY_NUMERIC);
                boolean selected = booleanOption && NbtAccess.getBooleanOr(settings, key, Boolean.TRUE.equals(initial));
                String value = booleanOption ? selected ? "On" : "Off" : NbtAccess.getStringOr(settings, key, enumDefault(key));
                yield new Presentation(actionIcon(action), label(action) + (value.isEmpty() ? "" : ": " + value), selected);
            }
        };
    }

    private static Boolean booleanDefault(String key) {
        return switch (key) {
            case "enabled", "external_output", "magnet_items", "magnet_xp", "feed_when_hurt", "swap_weapons",
                    "handlers", "hands", "mending", "inception_outer_inventory", "inception_inner_upgrades",
                    "inception_nested_first", "alchemy_match_duration", "alchemy_match_amplifier",
                    "alchemy_match_all", "alchemy_all_missing" -> true;
            case "shuffle", "work_in_gui", "compact_anything", "world", "grid_refill" -> false;
            default -> null;
        };
    }

    private static String enumDefault(String key) {
        return switch (key) {
            case "filter_direction", "alchemy_targets" -> "BOTH";
            case "void_mode" -> "STORAGE_OVERFLOW";
            case "hunger_mode" -> "HALF";
            case "tool_mode" -> "AUTO";
            case "repeat" -> "OFF";
            case "direction" -> "input";
            case "result_destination" -> "STORAGE";
            default -> "";
        };
    }

    private static Icon actionIcon(String action) {
        return switch (action) {
            case "play" -> Icon.PLAY;
            case "stop" -> Icon.STOP;
            case "previous" -> Icon.PREVIOUS;
            case "next" -> Icon.NEXT;
            case "shuffle" -> Icon.SHUFFLE;
            case "repeat" -> Icon.REPEAT;
            case "toggle", "mending" -> Icon.POWER;
            case "container", "store", "take", "store_all", "take_all" -> Icon.TRANSFER;
            case "tags", "input_tags" -> Icon.TAG;
            case "direction", "filter_direction", "external_output" -> Icon.DIRECTION;
            case "target_up", "levels_up" -> Icon.PLUS;
            case "target_down", "levels_down" -> Icon.MINUS;
            case "slot_rules" -> Icon.MEMORY;
            default -> Icon.FILTER;
        };
    }
    static String label(String action) {
        if (action.equals("toggle")) return "Enabled";
        if (action.equals("container")) return "Use cursor container";
        if (action.equals("external_output")) return "External energy output";
        if (action.equals("fluids")) return "Fluid filters";
        if (action.equals("slot_rules")) return "Slot rules";
        String words = action.replace('_', ' ').replace(':', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
