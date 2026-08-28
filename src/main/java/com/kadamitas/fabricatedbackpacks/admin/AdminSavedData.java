package com.kadamitas.fabricatedbackpacks.admin;

import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** Original world data, deliberately separate from both live inventories and settings templates. */
public final class AdminSavedData extends SavedData {
    private static final int MAX_TEMPLATES = 256;
    public static final Codec<AdminSavedData> CODEC = RecordCodecBuilder.<AdminSavedData>create(instance -> instance.group(
            Codec.intRange(1, 1).optionalFieldOf("format", 1).forGetter(value -> 1),
            Codec.unboundedMap(Codec.STRING, BackpackArchive.CODEC).optionalFieldOf("archives", Map.of()).forGetter(value -> value.archives),
            Codec.unboundedMap(Codec.STRING, WholeBagTemplate.CODEC).optionalFieldOf("templates", Map.of()).forGetter(value -> value.templates))
            .apply(instance, AdminSavedData::new)).validate(AdminSavedData::validate);
    public static final SavedDataType<AdminSavedData> TYPE = new SavedDataType<>(BackpackRegistry.id("administration"),
            AdminSavedData::new, CODEC, DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

    private final Map<String, BackpackArchive> archives;
    private final Map<String, WholeBagTemplate> templates;

    public AdminSavedData() { this(1, Map.of(), Map.of()); }
    private AdminSavedData(int format, Map<String, BackpackArchive> archives, Map<String, WholeBagTemplate> templates) {
        this.archives = new HashMap<>(archives);
        this.templates = new HashMap<>(templates);
    }
    public static AdminSavedData of(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(TYPE); }
    private static DataResult<AdminSavedData> validate(AdminSavedData value) {
        if (value.templates.size() > MAX_TEMPLATES || value.templates.keySet().stream().anyMatch(name -> !AdminNames.isLocal(name))
                || value.archives.entrySet().stream().anyMatch(entry -> !entry.getKey().equals(entry.getValue().identity())))
            return DataResult.error(() -> "Invalid administration data keys or template limit");
        return DataResult.success(value);
    }
    public Optional<BackpackArchive> archive(String identity) { return Optional.ofNullable(archives.get(identity)); }
    public List<BackpackArchive> archives(String owner) {
        return archives.values().stream().filter(entry -> owner == null || owner.isEmpty()
                        || entry.ownerId().equalsIgnoreCase(owner) || entry.ownerName().equalsIgnoreCase(owner))
                .sorted(Comparator.comparingLong(BackpackArchive::accessedAt).reversed().thenComparing(BackpackArchive::identity)).toList();
    }
    public void record(BackpackArchive entry) {
        BackpackArchive.validate(entry).getOrThrow();
        BackpackArchive previous = archives.get(entry.identity());
        if (previous != null && previous.playerBacked() && !entry.playerBacked())
            throw new IllegalArgumentException("Player-backed archives cannot lose their ownership protection");
        archives.put(entry.identity(), entry);
        setDirty();
    }
    public int cleanupNonPlayer(boolean onlyEmpty) {
        Predicate<BackpackArchive> removable = value -> !value.playerBacked() && (!onlyEmpty || BackpackArchives.isEmpty(value.backpack()));
        int before = archives.size();
        archives.values().removeIf(removable);
        int removed = before - archives.size();
        if (removed > 0) setDirty();
        return removed;
    }
    public List<String> templateNames() { return templates.keySet().stream().sorted().toList(); }
    public Optional<WholeBagTemplate> template(String name) { return Optional.ofNullable(templates.get(name)); }
    public void putTemplate(String name, ItemStack stack, boolean overwrite) {
        AdminNames.local(name);
        if (templates.containsKey(name) && !overwrite) throw new IllegalArgumentException("Template exists; specify overwrite explicitly");
        if (!templates.containsKey(name) && templates.size() >= MAX_TEMPLATES) throw new IllegalArgumentException("The world already has 256 local templates");
        WholeBagTemplate value = WholeBagTemplate.capture(stack);
        templates.put(name, value);
        setDirty();
    }
    public boolean deleteTemplate(String name) {
        AdminNames.local(name);
        if (templates.remove(name) == null) return false;
        setDirty();
        return true;
    }
}
