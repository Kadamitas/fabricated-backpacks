package com.kadamitas.fabricatedbackpacks.client.screen;

import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import com.kadamitas.fabricatedbackpacks.client.screen.BackpackIconButton.Icon;
import net.minecraft.nbt.CompoundTag;
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
            case "compacting" -> List.of("work_in_gui");
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
                String value = settings.getStringOr(key, initial);
                yield new Presentation(switch (value) {
                    case "BLOCK" -> Icon.FILTER_BLOCK;
                    case "CONTENTS" -> Icon.FILTER_CONTENTS;
                    default -> Icon.FILTER_ALLOW;
                }, label(action) + ": " + value, false);
            }
            case "filter_match" -> {
                String value = settings.getStringOr(key, "ITEM");
                yield new Presentation(switch (value) {
                    case "NAMESPACE" -> Icon.MATCH_MOD;
                    case "TAGS" -> Icon.MATCH_TAGS;
                    default -> Icon.MATCH_ITEM;
                }, label(action) + ": " + value, false);
            }
            case "match_damage", "match_components" -> {
                boolean match = settings.getBooleanOr(key, false);
                Icon icon = operation.equals("match_damage")
                        ? match ? Icon.MATCH_DAMAGE : Icon.IGNORE_DAMAGE
                        : match ? Icon.MATCH_COMPONENTS : Icon.IGNORE_COMPONENTS;
                yield new Presentation(icon, label(action) + (match ? ": On" : ": Off"), false);
            }
            case "tag_match" -> new Presentation(Icon.TAG, label(action) + ": " + settings.getStringOr(key, "ANY"), false);
            default -> {
                Boolean initial = booleanDefault(key);
                boolean booleanOption = initial != null || settings.getBoolean(key).isPresent();
                boolean selected = booleanOption && settings.getBooleanOr(key, Boolean.TRUE.equals(initial));
                String value = booleanOption ? selected ? "On" : "Off" : settings.getStringOr(key, enumDefault(key));
                yield new Presentation(actionIcon(action), label(action) + (value.isEmpty() ? "" : ": " + value), selected);
            }
        };
    }

    /** Shift-only context help; the current effective value and explanation update together. */
    static String help(String action, UpgradeKind kind, CompoundTag settings) {
        String explanation = switch (action) {
            case "toggle" -> "Turn this upgrade's automatic behavior on or off without removing it or clearing its settings.";
            case "play" -> "Start playback at the first occupied disc slot. An empty jukebox does nothing.";
            case "stop" -> "Stop this backpack's audio while keeping every disc and playback preference.";
            case "previous" -> "Return to the previously played occupied disc. This does nothing when no playback history exists.";
            case "next" -> "Advance to the next occupied disc; a manual next wraps at the end of the library.";
            case "shuffle" -> "Randomize the remaining occupied-disc order. The disc already playing is not restarted.";
            case "repeat" -> "Cycle OFF, ALL, and ONE. ALL restarts the library; ONE repeats the current disc after it finishes.";
            case "filter_mode", "input_filter_mode", "fuel_filter_mode" ->
                    "Cycle how this filter treats its choices: ALLOW accepts matches, BLOCK rejects matches, and CONTENTS derives choices from stored items when supported.";
            case "filter_match", "input_filter_match" ->
                    "Choose whether filter entries match the exact item, every item from the same mod namespace, or shared item tags.";
            case "match_damage", "input_match_damage" ->
                    "Choose whether durability and other damage values must match the filter entry.";
            case "match_components", "input_match_components" ->
                    "Choose whether components such as enchantments, potion contents, custom data, and names must match.";
            case "tag_match", "input_tag_match" ->
                    "Choose whether any selected tag may match or every selected tag must match.";
            case "tags", "input_tags" ->
                    "Open the namespaced item-tag editor for this filter, for example minecraft:logs.";
            case "filter_direction" ->
                    "Choose whether the storage filter controls items entering the backpack, leaving it, or both directions.";
            case "inception_outer_inventory" ->
                    "Allow an outer backpack to route storage operations through backpacks held inside it.";
            case "inception_inner_upgrades" ->
                    "Allow compatible upgrades installed in nested backpacks to participate in processing.";
            case "inception_nested_first" ->
                    "Try nested backpack storage before the outer backpack during routed insertion and extraction.";
            case "magnet_items" -> "Choose whether the magnet attracts eligible nearby item entities.";
            case "magnet_xp" -> "Choose whether the magnet attracts nearby experience orbs.";
            case "hunger_mode" ->
                    "Choose the hunger threshold for automatic feeding: half empty, fully empty, or any missing hunger.";
            case "feed_when_hurt" ->
                    "Allow automatic feeding while health is missing even when the selected hunger threshold is not met.";
            case "void_mode" ->
                    "Choose when matched items are destroyed: only when storage is full, when the target slot is full, or always when the server permits it.";
            case "work_in_gui" ->
                    "Allow this upgrade to continue automatic work while the backpack menu is open.";
            case "fluids" ->
                    "Open the fluid-filter editor. Rows copy the fluid in a filled cursor container without consuming it.";
            case "tool_mode" ->
                    "Choose how the tool swapper selects a stored tool for the block or entity being used.";
            case "swap_weapons" ->
                    "Allow the tool swapper to select matching weapons as well as mining tools.";
            case "alchemy_targets" ->
                    "Choose whether automatic potion use targets the wearer, nearby eligible entities, or both.";
            case "alchemy_match_duration" ->
                    "Require an existing effect's remaining duration to satisfy the configured potion rule.";
            case "alchemy_match_amplifier" ->
                    "Require an existing effect's strength to satisfy the configured potion rule.";
            case "alchemy_match_all" ->
                    "Require every effect on a multi-effect potion to pass before the potion is used.";
            case "alchemy_all_missing" ->
                    "Use a multi-effect potion only when all of its applicable effects are currently missing.";
            case "container" -> kind.family().equals("tank")
                    ? "Transfer fluid between this tank and the filled or empty container currently carried by the cursor."
                    : "Transfer energy between this battery and the compatible item currently carried by the cursor.";
            case "external_output" ->
                    "Allow a placed backpack battery to push stored energy into compatible neighboring receivers.";
            case "direction" -> kind.family().equals("xp_pump")
                    ? "Choose whether the XP pump stores player experience in the backpack or returns stored experience to the player."
                    : "Choose whether the pump imports fluid into the backpack tank or exports stored fluid.";
            case "handlers" ->
                    "Allow the pump to transfer with compatible adjacent fluid handlers.";
            case "hands" ->
                    "Allow the advanced pump to transfer with compatible containers held by the wearer.";
            case "world" ->
                    "Allow the advanced pump to collect or place fluid blocks in its configured world range.";
            case "store" -> "Move one configured XP step from the player into backpack experience storage.";
            case "take" -> "Move one configured XP step from backpack experience storage to the player.";
            case "store_all" -> "Move as much player experience as capacity and server limits allow into the backpack.";
            case "take_all" -> "Return as much stored backpack experience as the player can receive.";
            case "target_up" -> "Raise the automatic XP target level used by the pump.";
            case "target_down" -> "Lower the automatic XP target level used by the pump.";
            case "levels_up" -> "Increase the number of experience levels moved by each manual step.";
            case "levels_down" -> "Decrease the number of experience levels moved by each manual step.";
            case "mending" ->
                    "Allow stored experience to repair damaged equipment with Mending when the server setting permits it.";
            case "slot_rules" ->
                    "Open per-filter-slot rules such as refill limits or alchemy health conditions.";
            default -> "Adjust this upgrade setting. The change applies to this installed upgrade only.";
        };
        return presentation(action, kind, settings).label() + ". " + explanation;
    }

    private static Boolean booleanDefault(String key) {
        return switch (key) {
            case "enabled", "external_output", "magnet_items", "magnet_xp", "feed_when_hurt", "swap_weapons",
                    "handlers", "hands", "mending", "inception_outer_inventory", "inception_inner_upgrades",
                    "inception_nested_first", "alchemy_match_duration", "alchemy_match_amplifier",
                    "alchemy_match_all", "alchemy_all_missing" -> true;
            case "shuffle", "work_in_gui", "world", "grid_refill" -> false;
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
