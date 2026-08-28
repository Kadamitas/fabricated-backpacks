package com.kadamitas.fabricatedbackpacks.gameplay;

import com.kadamitas.fabricatedbackpacks.admin.AdminNames;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import com.kadamitas.fabricatedbackpacks.domain.UpgradeKind;
import com.kadamitas.fabricatedbackpacks.equipment.BackpackEquipment;
import com.kadamitas.fabricatedbackpacks.registry.BackpackRegistry;
import com.kadamitas.fabricatedbackpacks.storage.BagComponents;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InventorySnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Repairs identity collisions between physical stacks without inferring that their contents are duplicates. */
public final class BackpackIdentities {
    private static final int INTERVAL = 20;
    private static final int MAX_NEARBY_DROPS = 128;
    private static final double DROP_RADIUS = 8;
    private static final Map<MinecraftServer, DropPass> DROPS = new IdentityHashMap<>();

    private BackpackIdentities() {}

    public static void tick(MinecraftServer server) {
        if (!BackpackConfig.get().storage().disableDuplicateChecks() && server.getTickCount() % INTERVAL == 0) scan(server);
    }

    /** Called before player and block automation. Returns colliding or malformed identities replaced. */
    public static int scan(MinecraftServer server) {
        if (BackpackConfig.get().storage().disableDuplicateChecks()) return 0;
        Scan scan = new Scan();
        List<ServerPlayer> players = orderedPlayers(server.getPlayerList().getPlayers());
        scan.players(players);
        for (ServerPlayer player : players) {
            for (ItemEntity entity : nearby(player.serverLevel(), player.getBoundingBox().inflate(DROP_RADIUS))) {
                scan.drop(entity);
                pass(server).entities.add(entity.getUUID());
            }
        }
        return scan.repair();
    }

    /** Also covers dropped bags in loaded areas without a nearby carrier. */
    public static void tickDropped(ItemEntity entity) {
        if (BackpackConfig.get().storage().disableDuplicateChecks()
                || !(entity.level() instanceof ServerLevel level) || !valid(entity.getItem()) || entity.isRemoved()
                || level.getServer().getTickCount() % INTERVAL != 0) return;
        if (pass(level.getServer()).entities.contains(entity.getUUID())) return;
        scanNearby(entity);
    }

    public static int scanNearby(ItemEntity origin) {
        if (BackpackConfig.get().storage().disableDuplicateChecks()
                || !(origin.level() instanceof ServerLevel level) || origin.isRemoved() || !valid(origin.getItem())) return 0;
        AABB area = origin.getBoundingBox().inflate(DROP_RADIUS);
        Scan scan = new Scan();
        scan.players(orderedPlayers(level.getServer().getPlayerList().getPlayers().stream()
                .filter(player -> player.serverLevel() == level && player.getBoundingBox().intersects(area)).toList()));
        scan.drop(origin);
        pass(level.getServer()).entities.add(origin.getUUID());
        for (ItemEntity entity : nearby(level, area)) {
            scan.drop(entity);
            pass(level.getServer()).entities.add(entity.getUUID());
        }
        return scan.repair();
    }

    public static void stop(MinecraftServer server) { DROPS.remove(server); }

    private static boolean valid(ItemStack stack) { return BackpackRegistry.isBackpack(stack) && stack.getCount() == 1; }

    private static List<ServerPlayer> orderedPlayers(List<ServerPlayer> players) {
        return players.stream().sorted(Comparator.comparing(player -> player.getUUID().toString())).toList();
    }

    private static List<ItemEntity> nearby(ServerLevel level, AABB area) {
        return level.getEntitiesOfClass(ItemEntity.class, area, entity -> !entity.isRemoved() && valid(entity.getItem()))
                .stream().sorted(Comparator.comparing(entity -> entity.getUUID().toString())).limit(MAX_NEARBY_DROPS).toList();
    }

    private static DropPass pass(MinecraftServer server) {
        DropPass pass = DROPS.computeIfAbsent(server, ignored -> new DropPass());
        if (pass.tick != server.getTickCount()) { pass.tick = server.getTickCount(); pass.entities.clear(); }
        return pass;
    }

    private static final class DropPass {
        int tick = Integer.MIN_VALUE;
        final Set<UUID> entities = new HashSet<>();
    }

    private static final class Candidate {
        final BagInventory bag;
        final Set<ItemStack> aliases = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<Root> roots = new LinkedHashSet<>();
        String key;

        Candidate(BagInventory bag, String key) { this.bag = bag; this.key = key; aliases.add(bag.stack()); }
    }

    private static final class Root {
        final Candidate candidate;
        final String key;
        final ServerPlayer player;
        final boolean equipped;
        final int slot;
        final ItemEntity entity;
        final ItemStack source;
        boolean dirty;

        Root(Candidate candidate, String key, ServerPlayer player, boolean equipped, int slot, ItemEntity entity, ItemStack source) {
            this.candidate = candidate; this.key = key; this.player = player; this.equipped = equipped;
            this.slot = slot; this.entity = entity; this.source = source;
        }

        void publish() {
            if (!dirty) return;
            BagInventory bag = candidate.bag;
            bag.save();
            if (player != null) {
                if (equipped) BackpackEquipment.setFromInventory(player, bag);
                else if (player.getInventory().getItem(slot) == source) player.getInventory().setChanged();
            } else if (entity != null && !entity.isRemoved() && entity.getItem() == source) {
                // A new value is needed to mark the entity's synchronized item data dirty.
                entity.setItem(bag.stack().copy());
            }
        }
    }

    private static final class Scan {
        final Map<ItemStack, Candidate> physical = new IdentityHashMap<>();
        final List<Candidate> candidates = new ArrayList<>();
        final List<Root> roots = new ArrayList<>();
        final Set<ItemEntity> drops = Collections.newSetFromMap(new IdentityHashMap<>());

        void players(List<ServerPlayer> players) {
            // Register canonical equipment before any inventory aliases of its published stack.
            for (ServerPlayer player : players) {
                ItemStack attached = BackpackEquipment.get(player);
                if (!valid(attached)) continue;
                var before = attached.getComponentsPatch();
                BagInventory bag = BackpackEquipment.inventory(player).orElseThrow();
                Root root = root(bag, "0:" + player.getUUID() + ":0", player, true, -1, null, attached);
                physical.put(attached, root.candidate);
                root.candidate.aliases.add(attached);
                root.dirty |= !before.equals(bag.stack().getComponentsPatch());
            }
            for (ServerPlayer player : players) {
                for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                    ItemStack stack = player.getInventory().getItem(slot);
                    if (!valid(stack)) continue;
                    var before = stack.getComponentsPatch();
                    Candidate existing = physical.get(stack);
                    BagInventory bag = existing == null ? BagInventory.of(stack) : existing.bag;
                    Root root = root(bag, "0:" + player.getUUID() + ":1:" + (1000 + slot), player, false, slot, null, stack);
                    root.dirty |= !before.equals(stack.getComponentsPatch());
                }
            }
        }

        void drop(ItemEntity entity) {
            if (!drops.add(entity)) return;
            ItemStack stack = entity.getItem();
            if (!valid(stack)) return;
            var before = stack.getComponentsPatch();
            Candidate existing = physical.get(stack);
            BagInventory bag = existing == null ? BagInventory.of(stack) : existing.bag;
            Root root = root(bag, "1:" + entity.getUUID(), null, false, -1, entity, stack);
            root.dirty |= !before.equals(stack.getComponentsPatch());
        }

        Candidate candidate(BagInventory bag, String key) {
            Candidate candidate = physical.get(bag.stack());
            if (candidate == null) {
                candidate = new Candidate(bag, key);
                physical.put(bag.stack(), candidate);
                candidates.add(candidate);
            } else if (key.compareTo(candidate.key) < 0) candidate.key = key;
            return candidate;
        }

        Root root(BagInventory bag, String key, ServerPlayer player, boolean equipped, int slot, ItemEntity entity, ItemStack source) {
            Candidate candidate = candidate(bag, key);
            Root root = new Root(candidate, key, player, equipped, slot, entity, source);
            candidate.roots.add(root);
            roots.add(root);
            return root;
        }

        void children() {
            for (Root root : roots) {
                BagInventory bag = root.candidate.bag;
                if (!bag.has(UpgradeKind.INCEPTION)) continue;
                for (int slot = 0; slot < Math.min(bag.getContainerSize(), InventorySnapshot.MAX_SLOTS); slot++) {
                    // Use physical cells: processing traversal intentionally excludes duplicate UUIDs.
                    ItemStack stack = bag.getItems().get(slot);
                    if (!valid(stack) || stack == bag.stack()) continue;
                    var before = stack.getComponentsPatch();
                    Candidate existing = physical.get(stack);
                    BagInventory child = existing == null ? BagInventory.of(stack) : existing.bag;
                    Candidate candidate = candidate(child, root.key + ":child:" + (1000 + slot));
                    candidate.roots.add(root);
                    root.dirty |= !before.equals(stack.getComponentsPatch());
                }
            }
        }

        int repair() {
            children();
            candidates.sort(Comparator.<Candidate>comparingInt(candidate -> candidate.bag.getContainerSize()).reversed()
                    .thenComparing(candidate -> candidate.key));
            Set<String> reserved = new HashSet<>();
            for (Candidate candidate : candidates) if (AdminNames.isIdentity(candidate.bag.identity())) reserved.add(candidate.bag.identity());
            Set<String> kept = new HashSet<>();
            int repaired = 0;
            for (Candidate candidate : candidates) {
                String identity = candidate.bag.identity();
                if (AdminNames.isIdentity(identity) && kept.add(identity)) continue;
                String replacement;
                do { replacement = UUID.randomUUID().toString(); } while (!reserved.add(replacement));
                kept.add(replacement);
                for (ItemStack alias : candidate.aliases) alias.set(BagComponents.IDENTITY, replacement);
                candidate.roots.forEach(root -> root.dirty = true);
                repaired++;
            }
            roots.forEach(Root::publish);
            return repaired;
        }
    }
}
