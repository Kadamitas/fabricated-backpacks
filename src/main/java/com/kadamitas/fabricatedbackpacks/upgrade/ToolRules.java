package com.kadamitas.fabricatedbackpacks.upgrade;

import com.kadamitas.fabricatedbackpacks.FabricatedBackpacks;
import com.kadamitas.fabricatedbackpacks.config.RuleMatchers;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Server-owned rules publish atomically after a successful data-pack reload. */
public final class ToolRules {
    public static final String DIRECTORY = "backpack_tools";
    private static final int MAX_RULES = 1_024;
    private static final Map<MinecraftServer, Catalog> CATALOGS = Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean initialized;
    private record Entry(ResourceLocation id, ToolRule rule) { }
    private record Catalog(List<Entry> ordered, Map<ResourceLocation, ToolRule> byId) { }
    private ToolRules() { }

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ServerLifecycleEvents.SERVER_STARTED.register(ToolRules::reload);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> { if (success) reload(server); });
        ServerLifecycleEvents.SERVER_STOPPED.register(CATALOGS::remove);
    }

    /** Invalid data preserves the previous complete catalog and is reported in the server log. */
    public static boolean reload(MinecraftServer server) {
        try {
            CATALOGS.put(server, load(server));
            return true;
        } catch (IOException | RuntimeException invalid) {
            FabricatedBackpacks.LOGGER.warn("Backpack tool rules were not replaced: {}", invalid.getMessage());
            return false;
        }
    }

    public static Map<ResourceLocation, ToolRule> rules(MinecraftServer server) { return catalog(server).byId(); }

    static boolean recognizes(MinecraftServer server, ItemStack item) {
        return catalog(server).ordered().stream().anyMatch(entry -> RuleMatchers.item(item, entry.rule().items()));
    }

    static ToolRule forBlock(MinecraftServer server, BlockState block, ItemStack item, boolean manual) {
        for (Entry entry : catalog(server).ordered()) {
            ToolRule rule = entry.rule();
            if ((!rule.manualOnly() || manual) && RuleMatchers.block(block, rule.blocks()) && RuleMatchers.item(item, rule.items())) return rule;
        }
        return null;
    }

    static ToolRule forEntity(MinecraftServer server, LivingEntity target, ItemStack item, boolean manual) {
        for (Entry entry : catalog(server).ordered()) {
            ToolRule rule = entry.rule();
            if ((!rule.manualOnly() || manual) && RuleMatchers.entity(target, rule.entities()) && RuleMatchers.item(item, rule.items())) return rule;
        }
        return null;
    }

    private static Catalog catalog(MinecraftServer server) {
        Catalog result = CATALOGS.get(server);
        if (result != null) return result;
        if (reload(server)) return CATALOGS.get(server);
        Catalog empty = new Catalog(List.of(), Map.of());
        CATALOGS.put(server, empty);
        return empty;
    }

    private static Catalog load(MinecraftServer server) throws IOException {
        var resources = server.getResourceManager().listResources(DIRECTORY, id -> id.getPath().endsWith(".json"));
        if (resources.size() > MAX_RULES) throw new IOException("Tool rule catalog exceeds 1024 files");
        List<Entry> entries = new ArrayList<>();
        for (var source : resources.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
            ResourceLocation path = source.getKey();
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(path.getNamespace(), path.getPath().substring(DIRECTORY.length() + 1, path.getPath().length() - 5));
            try (var stream = source.getValue().open()) {
                byte[] bytes = stream.readNBytes(ToolRule.MAX_BYTES + 1);
                if (bytes.length > ToolRule.MAX_BYTES) throw new IOException("Tool rule exceeds 64 KiB: " + path);
                entries.add(new Entry(id, ToolRule.decode(new String(bytes, StandardCharsets.UTF_8))));
            } catch (RuntimeException invalid) { throw new IOException("Invalid tool rule " + path + ": " + invalid.getMessage(), invalid); }
        }
        entries.sort(Comparator.<Entry>comparingInt(entry -> entry.rule().priority()).reversed().thenComparing(Entry::id));
        Map<ResourceLocation, ToolRule> rules = new LinkedHashMap<>();
        entries.forEach(entry -> rules.put(entry.id(), entry.rule()));
        return new Catalog(List.copyOf(entries), Collections.unmodifiableMap(rules));
    }
}
