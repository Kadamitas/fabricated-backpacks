package com.kadamitas.fabricatedbackpacks.upgrade;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/** A bounded, immutable rule from a server's backpack_tools data directory. */
public record ToolRule(Set<String> items, Set<String> blocks, Set<String> entities,
                       int priority, boolean manualOnly, boolean requireCorrectTool) {
    public static final int MAX_BYTES = 65_536;
    private static final int MAX_SELECTORS = 64;
    private static final Set<String> KEYS = Set.of("items", "blocks", "entities", "priority", "manual_only", "require_correct_tool");

    public ToolRule {
        items = validated(items);
        blocks = validated(blocks);
        entities = validated(entities);
        if (items.isEmpty()) throw new IllegalArgumentException("Tool rule requires at least one item selector");
        if (blocks.isEmpty() && entities.isEmpty()) throw new IllegalArgumentException("Tool rule requires block or entity targets");
        if (priority < 0 || priority > 1_000) throw new IllegalArgumentException("Tool priority must be between 0 and 1000");
    }

    public static ToolRule decode(String json) {
        if (json == null || json.length() > MAX_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Tool rule exceeds 64 KiB");
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("Tool rule must be an object");
        JsonObject object = parsed.getAsJsonObject();
        for (String key : object.keySet()) if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown tool rule field: " + key);
        int priority = 0;
        if (object.has("priority")) {
            JsonElement value = object.get("priority");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Tool priority must be an integer");
            try { priority = value.getAsBigDecimal().intValueExact(); }
            catch (ArithmeticException invalid) { throw new IllegalArgumentException("Tool priority must be an integer", invalid); }
        }
        return new ToolRule(selectors(object, "items"), selectors(object, "blocks"), selectors(object, "entities"), priority,
                bool(object, "manual_only", false), bool(object, "require_correct_tool", true));
    }

    private static Set<String> selectors(JsonObject object, String key) {
        if (!object.has(key)) return Set.of();
        JsonElement value = object.get(key);
        if (!value.isJsonArray() || value.getAsJsonArray().size() > MAX_SELECTORS) throw new IllegalArgumentException(key + " must be an array of at most 64 selectors");
        Set<String> result = new LinkedHashSet<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " selectors must be strings");
            if (!result.add(element.getAsString())) throw new IllegalArgumentException("Duplicate " + key + " selector: " + element.getAsString());
        }
        return result;
    }

    private static Set<String> validated(Set<String> selectors) {
        if (selectors == null || selectors.size() > MAX_SELECTORS) throw new IllegalArgumentException("Tool selector count exceeds 64");
        for (String selector : selectors) if (selector == null || selector.length() > 256
                || !selector.matches("#?[a-z0-9_.-]+:[a-z0-9_/.-]+")) throw new IllegalArgumentException("Invalid tool selector: " + selector);
        return Set.copyOf(selectors);
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + " must be a boolean");
        return value.getAsBoolean();
    }
}
