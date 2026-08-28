package com.kadamitas.fabricatedbackpacks.upgrade;

import com.kadamitas.fabricatedbackpacks.domain.Playlist;
import com.kadamitas.fabricatedbackpacks.network.JukeboxAudio;
import com.kadamitas.fabricatedbackpacks.storage.BagInventory;
import com.kadamitas.fabricatedbackpacks.storage.InstalledUpgrade;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Disc slots and preferences persist; queue/history/audio instances intentionally do not. */
public final class JukeboxRuntime {
    public interface SoundBridge {
        /** identity must key a separate client instance even when two backpacks play the same song. */
        void start(ServerLevel level, String identity, int entityId, BlockPos position, Holder<JukeboxSong> song);
        void stop(ServerLevel level, String identity);
        default void join(ServerPlayer listener, String identity, int entityId, BlockPos position,
                          Holder<JukeboxSong> song, int remainingTicks) {}
        default void leave(ServerPlayer listener, String identity) {}
    }

    private record Key(MinecraftServer server, String bag, int slot) { }
    private static final Map<Key, Session> SESSIONS = new HashMap<>();
    private static SoundBridge sound;
    private static boolean trackingHooksRegistered;

    private static final class Session {
        final String soundIdentity;
        final String upgradeIdentity;
        final RandomGenerator random;
        final Set<UUID> listeners = new HashSet<>();
        ServerLevel level;
        BlockPos position;
        int entityId;
        BagInventory bag;
        InstalledUpgrade upgrade;
        List<ItemStack> discs;
        Playlist playlist;
        long finishTick;
        long startedTick;
        int lastSeen;
        boolean listenersDirty;

        Session(BagInventory bag, InstalledUpgrade upgrade, ServerLevel level, BlockPos position, LivingEntity carrier) {
            this.bag = bag;
            this.upgrade = upgrade;
            this.level = level;
            this.position = position.immutable();
            entityId = carrier == null ? -1 : carrier.getId();
            CompoundTag state = bag.settings(upgrade);
            upgradeIdentity = state.getStringOr("jukebox_identity", UUID.randomUUID().toString());
            soundIdentity = bag.identity() + ":" + upgrade.slot() + ":" + upgradeIdentity;
            random = new Random(soundIdentity.hashCode() ^ level.getGameTime());
            Container inventory = bag.upgradeInventory(upgrade);
            discs = discSnapshot(inventory);
            playlist = Playlist.stopped(discs.size(), occupied(discs),
                    state.getBooleanOr("shuffle", false), repeat(state));
            lastSeen = level.getServer().getTickCount();
            bag.updateSettings(upgrade, tag -> tag.putString("jukebox_identity", upgradeIdentity));
            persist(this);
        }
    }

    private JukeboxRuntime() { }

    public static void setSoundBridge(SoundBridge bridge) {
        sound = Objects.requireNonNull(bridge);
        if (!trackingHooksRegistered) {
            EntityTrackingEvents.STOP_TRACKING.register((entity, listener) -> trackingChanged(entity, listener, true));
            EntityTrackingEvents.START_TRACKING.register((entity, listener) -> trackingChanged(entity, listener, false));
            trackingHooksRegistered = true;
        }
    }
    public static boolean isDisc(ItemStack item) { return !item.isEmpty() && JukeboxSong.fromStack(item).isPresent(); }

    public static void tick(BagInventory bag, InstalledUpgrade upgrade, ServerLevel level, BlockPos position, LivingEntity carrier) {
        Session session = session(bag, upgrade, level, position, carrier);
        refreshDiscs(session);
        if (!session.playlist.playing()) return;
        if (level.getGameTime() >= session.finishTick) {
            session.playlist = session.playlist.finished(session.random);
            restartSound(session);
        }
        if (session.playlist.playing() && (session.listenersDirty || level.getGameTime() % 20 == 0)) {
            reconcileListeners(session);
        }
        if (session.playlist.playing() && level.getGameTime() % 20 == 0) {
            level.sendParticles(ParticleTypes.NOTE, position.getX() + .5, position.getY() + 1.1, position.getZ() + .5,
                    1, .3, .1, .3, 0);
        }
    }

    public static boolean action(BagInventory bag, InstalledUpgrade upgrade, ServerLevel level, BlockPos position,
                                 LivingEntity carrier, String action) {
        Session session = session(bag, upgrade, level, position, carrier);
        refreshDiscs(session);
        boolean advanced = upgrade.kind().advanced();
        Playlist before = session.playlist;
        switch (action) {
            case "play" -> session.playlist = before.play(session.random);
            case "stop" -> session.playlist = before.stop();
            case "next" -> { if (advanced) session.playlist = before.next(session.random); }
            case "previous", "prev" -> { if (advanced) session.playlist = before.previous(); }
            case "shuffle" -> { if (advanced) session.playlist = before.setShuffle(!before.shuffle(), session.random); }
            case "repeat" -> {
                if (advanced) session.playlist = before.withRepeat(Playlist.Repeat.values()[(before.repeat().ordinal() + 1) % Playlist.Repeat.values().length]);
            }
            default -> { return false; }
        }
        boolean changeTrack = before.activeSlot() != session.playlist.activeSlot()
                || (before.playing() && action.equals("next") && advanced);
        if (changeTrack) restartSound(session);
        else persist(session);
        return !before.equals(session.playlist) || changeTrack;
    }

    private static Session session(BagInventory bag, InstalledUpgrade upgrade, ServerLevel level, BlockPos position, LivingEntity carrier) {
        Key key = new Key(level.getServer(), bag.identity(), upgrade.slot());
        Session old = SESSIONS.get(key);
        String id = bag.settings(upgrade).getStringOr("jukebox_identity", "");
        int carrierId = carrier == null ? -1 : carrier.getId();
        if (old != null && (old.level != level || !old.upgradeIdentity.equals(id)
                || old.entityId != carrierId || (carrierId == -1 && !old.position.equals(position)))) {
            stop(old, false);
            SESSIONS.remove(key);
            old = null;
        }
        if (old == null) {
            old = new Session(bag, upgrade, level, position, carrier);
            SESSIONS.put(key, old);
        }
        old.bag = bag;
        old.upgrade = upgrade;
        old.position = position.immutable();
        old.lastSeen = level.getServer().getTickCount();
        return old;
    }

    private static List<Integer> occupied(List<ItemStack> discs) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < discs.size(); slot++) if (isDisc(discs.get(slot))) slots.add(slot);
        return slots;
    }

    private static Playlist.Repeat repeat(CompoundTag state) {
        try { return Playlist.Repeat.valueOf(state.getStringOr("repeat", "OFF")); }
        catch (IllegalArgumentException invalid) { return Playlist.Repeat.OFF; }
    }

    private static void refreshDiscs(Session session) {
        List<ItemStack> current = discSnapshot(session.bag.upgradeInventory(session.upgrade));
        Set<Integer> changed = new HashSet<>();
        for (int slot = 0; slot < current.size(); slot++) {
            if (slot >= session.discs.size() || !ItemStack.matches(current.get(slot), session.discs.get(slot))) changed.add(slot);
        }
        for (int slot = current.size(); slot < session.discs.size(); slot++) changed.add(slot);
        boolean playing = session.playlist.playing();
        session.playlist = session.playlist.updateSlots(current.size(), occupied(current), changed, session.random);
        session.discs = current;
        if (playing && !session.playlist.playing()) stop(session);
    }

    private static List<ItemStack> discSnapshot(Container inventory) {
        // A malformed old auxiliary component may retain extra physical items;
        // only the supported 1..16 record slots participate in audio playback.
        int slots = Math.min(inventory.getContainerSize(), Playlist.MAX_SLOTS);
        if (slots == 0) return List.of(ItemStack.EMPTY);
        List<ItemStack> result = new ArrayList<>(slots);
        for (int slot = 0; slot < slots; slot++) result.add(inventory.getItem(slot).copy());
        return result;
    }

    private static void restartSound(Session session) {
        if (sound == null) throw new IllegalStateException("Register a scoped jukebox sound bridge before playback");
        sound.stop(session.level, session.soundIdentity);
        session.listeners.clear();
        session.listenersDirty = false;
        var resolved = session.playlist.playing() ? currentSong(session) : Optional.<Holder<JukeboxSong>>empty();
        if (resolved.isPresent()) {
            Holder<JukeboxSong> song = resolved.orElseThrow();
            session.startedTick = session.level.getGameTime();
            session.finishTick = session.startedTick + JukeboxAudio.boundedLengthTicks(song.value()) + 20L;
            sound.start(session.level, session.soundIdentity, session.entityId, session.position, song);
            listeners(session.level, session.position, session.entityId).forEach(player -> session.listeners.add(player.getUUID()));
        } else {
            session.playlist = session.playlist.stop();
            session.startedTick = 0;
            session.finishTick = 0;
        }
        persist(session);
    }

    /** Item defaults may hold a bootstrap registry reference rather than this world's datapack value. */
    private static Optional<Holder<JukeboxSong>> currentSong(Session session) {
        return JukeboxSong.fromStack(session.discs.get(session.playlist.activeSlot()))
                .flatMap(Holder::unwrapKey).filter(key -> key.isFor(Registries.JUKEBOX_SONG))
                .filter(key -> key.identifier().toString().length() <= JukeboxAudio.MAX_SONG_KEY_LENGTH)
                .flatMap(key -> session.level.registryAccess().lookup(Registries.JUKEBOX_SONG)
                        .flatMap(registry -> registry.get(key)))
                .filter(holder -> Float.isFinite(holder.value().lengthInSeconds()) && holder.value().lengthInSeconds() > 0)
                .map(holder -> holder);
    }

    /** Only tracked sources can produce a moving sound on the receiving client. */
    public static List<ServerPlayer> listeners(ServerLevel level, BlockPos position, int entityId) {
        Set<ServerPlayer> candidates = new HashSet<>();
        var carrier = entityId < 0 ? null : level.getEntity(entityId);
        if (carrier != null) {
            candidates.addAll(net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(carrier));
            if (carrier instanceof ServerPlayer owner) candidates.add(owner);
        } else if (entityId < 0) candidates.addAll(net.fabricmc.fabric.api.networking.v1.PlayerLookup.tracking(level, position));
        return candidates.stream().filter(player -> player.level() == level
                && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(position)) <= 128 * 128).toList();
    }

    private static void trackingChanged(Entity entity, ServerPlayer listener, boolean stopped) {
        for (Session session : SESSIONS.values()) {
            if (session.level != entity.level() || session.entityId != entity.getId() || !session.playlist.playing()) continue;
            // A client discards a moving sound when its carrier is removed. Even a
            // leave/re-pair within one tick must invalidate the previous delivery.
            if (session.listeners.remove(listener.getUUID()) && stopped && sound != null) {
                sound.leave(listener, session.soundIdentity);
            }
            // Resolve the new tracking set on the bag's next tick, after vanilla
            // pairing data, rather than replaying from inside a tracking callback.
            session.listenersDirty = true;
        }
    }

    private static void reconcileListeners(Session session) {
        session.listenersDirty = false;
        if (sound == null) return;
        var song = currentSong(session);
        if (song.isEmpty()) { stop(session); return; }
        var current = listeners(session.level, session.position, session.entityId);
        Set<UUID> present = new HashSet<>();
        int remaining = Math.toIntExact(Math.clamp(session.finishTick - session.level.getGameTime() - 20, 0, Integer.MAX_VALUE));
        for (ServerPlayer listener : current) {
            present.add(listener.getUUID());
            if (remaining > 0 && !session.listeners.contains(listener.getUUID())) {
                sound.join(listener, session.soundIdentity, session.entityId, session.position, song.orElseThrow(), remaining);
            }
        }
        for (UUID departed : session.listeners) if (!present.contains(departed)) {
            ServerPlayer listener = session.level.getServer().getPlayerList().getPlayer(departed);
            if (listener != null) sound.leave(listener, session.soundIdentity);
        }
        session.listeners.clear();
        session.listeners.addAll(present);
    }

    private static void persist(Session session) {
        session.bag.updateSettings(session.upgrade, tag -> {
            tag.putBoolean("playing", session.playlist.playing());
            tag.putInt("active_slot", session.playlist.activeSlot());
            tag.putLong("song_started", session.startedTick);
            tag.putLong("song_finish", session.finishTick);
            tag.putBoolean("shuffle", session.playlist.shuffle());
            tag.putString("repeat", session.playlist.repeat().name());
        });
    }

    private static void stop(Session session) {
        stop(session, true);
    }

    private static void stop(Session session, boolean writeLiveState) {
        if (sound != null) sound.stop(session.level, session.soundIdentity);
        session.playlist = session.playlist.stop();
        session.finishTick = 0;
        session.startedTick = 0;
        session.listeners.clear();
        session.listenersDirty = false;
        // A missing keepalive may refer to an old inventory handle. Saving it could overwrite newer contents.
        if (writeLiveState) persist(session);
    }

    public static void stopUpgrade(BagInventory bag, int slot, MinecraftServer server) {
        Session removed = SESSIONS.remove(new Key(server, bag.identity(), slot));
        if (removed != null) {
            InstalledUpgrade current = bag.installedUpgrades().stream().filter(upgrade -> upgrade.slot() == slot
                    && bag.settings(upgrade).getStringOr("jukebox_identity", "").equals(removed.upgradeIdentity)).findFirst().orElse(null);
            if (current != null) { removed.bag = bag; removed.upgrade = current; }
            stop(removed, current != null);
        }
    }

    /** Missing bag ticks cover removal, logout, dimension changes, and unloaded placed storage. */
    public static void endServerTick(MinecraftServer server) {
        if (server.getTickCount() % 10 != 0) return;
        Iterator<Map.Entry<Key, Session>> iterator = SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Session> entry = iterator.next();
            if (entry.getKey().server() == server && server.getTickCount() - entry.getValue().lastSeen > 10) {
                stop(entry.getValue(), false);
                iterator.remove();
            }
        }
    }

    public static void stopAll(MinecraftServer server) {
        Iterator<Map.Entry<Key, Session>> iterator = SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Key, Session> entry = iterator.next();
            if (entry.getKey().server() == server) { stop(entry.getValue(), false); iterator.remove(); }
        }
    }
}
