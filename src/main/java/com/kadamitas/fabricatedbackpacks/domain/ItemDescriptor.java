package com.kadamitas.fabricatedbackpacks.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable item identity for rule evaluation; component values must use a canonical encoding. */
public record ItemDescriptor(String id, int damage, Map<String, String> components, Set<String> tags) {
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    public static final ItemDescriptor EMPTY = new ItemDescriptor("minecraft:air", 0, Map.of(), Set.of());

    public ItemDescriptor {
        requireId(id);
        if (damage < 0) throw new IllegalArgumentException("Damage cannot be negative");
        Map<String, String> copy = new HashMap<>(Objects.requireNonNull(components, "components"));
        copy.forEach((key, value) -> {
            requireId(key);
            Objects.requireNonNull(value, "component value");
        });
        // Damage has its own matching switch and must never leak into component equality.
        copy.remove("minecraft:damage");
        components = Map.copyOf(copy);
        tags = Set.copyOf(Objects.requireNonNull(tags, "tags"));
        tags.forEach(ItemDescriptor::requireId);
    }

    public ItemDescriptor(String id) { this(id, 0, Map.of(), Set.of()); }

    public String namespace() { return id.substring(0, id.indexOf(':')); }
    public boolean isEmpty() { return id.equals("minecraft:air"); }

    /** Registry tags are reloadable metadata, not part of a ghost item's identity. */
    public boolean sameItemAndComponents(ItemDescriptor other) {
        Objects.requireNonNull(other, "other");
        return id.equals(other.id) && damage == other.damage && components.equals(other.components);
    }

    static void requireId(String id) {
        if (id == null || !RESOURCE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Expected a namespaced resource identifier: " + id);
        }
    }
}
