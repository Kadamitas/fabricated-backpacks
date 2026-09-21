package com.kadamitas.fabricatedbackpacks.platform;

import com.google.common.collect.MapMaker;
import com.mojang.serialization.Codec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Forge-persisted entity data with explicit owner-only versus public visual synchronization. */
public final class NativeAttachmentType<T> {
    public enum Sync { NONE, OWNER, TRACKING }
    public static final Map<Identifier, NativeAttachmentType<?>> TYPES = new LinkedHashMap<>();
    private final Identifier id;
    private final Supplier<T> initial;
    private final Codec<T> codec;
    private final boolean persistent, copyOnDeath;
    private final Sync sync;
    // Entity.equals and hashCode compare numeric ids, which the integrated client shares with
    // its server in one JVM. Identity keys keep the client's accepted copy from replacing the
    // server's live value (and the open equipped menu it is validated against).
    private final Map<Entity, T> live = new MapMaker().weakKeys().makeMap();
    public NativeAttachmentType(Identifier id, Supplier<T> initial, Codec<T> codec, boolean persistent, boolean copyOnDeath, Sync sync) {
        this.id = id; this.initial = initial; this.codec = codec; this.persistent = persistent; this.copyOnDeath = copyOnDeath; this.sync = sync;
        if (TYPES.putIfAbsent(id, this) != null) throw new IllegalStateException("Duplicate entity data key " + id);
    }
    public T get(Entity entity) { T value = getExisting(entity); if (value != null) return value; value = initial.get(); live.put(entity, value); return value; }
    public T getExisting(Entity entity) {
        T value = live.get(entity); if (value != null) return value;
        var stored = persistent ? entity.getPersistentData().get(id.toString()) : null;
        if (stored != null) { value = codec.parse(RegistryOps.create(NbtOps.INSTANCE, entity.registryAccess()), stored).getOrThrow(); live.put(entity, value); }
        return value;
    }
    public void set(Entity entity, T value) {
        live.put(entity, value);
        if (persistent) entity.getPersistentData().put(id.toString(), codec.encodeStart(RegistryOps.create(NbtOps.INSTANCE, entity.registryAccess()), value).getOrThrow());
        if (!entity.level().isClientSide()) synchronize(entity);
    }
    public void remove(Entity entity) { live.remove(entity); entity.getPersistentData().remove(id.toString()); if (!entity.level().isClientSide()) synchronize(entity); }
    private CompoundTag encode(Entity entity) { var tag = new CompoundTag(); tag.put("value", codec.encodeStart(RegistryOps.create(NbtOps.INSTANCE, entity.registryAccess()), get(entity)).getOrThrow()); return tag; }
    public void acceptClient(Entity entity, CompoundTag encoded) { if (sync != Sync.NONE) live.put(entity, codec.parse(RegistryOps.create(NbtOps.INSTANCE, entity.registryAccess()), encoded.get("value")).getOrThrow()); }
    private void send(Entity entity, ServerPlayer recipient) {
        if (recipient.connection != null && recipient.connection.isAcceptingMessages())
            com.kadamitas.fabricatedbackpacks.platform.network.ServerPlayNetworking.send(recipient, new com.kadamitas.fabricatedbackpacks.platform.network.AttachmentPayload(entity.getId(), id, encode(entity)));
    }
    private void synchronize(Entity entity) {
        if (sync == Sync.NONE) return;
        if (entity instanceof ServerPlayer owner) send(entity, owner);
        if (sync == Sync.TRACKING) for (ServerPlayer viewer : com.kadamitas.fabricatedbackpacks.platform.network.PlayerLookup.tracking(entity)) if (viewer != entity) send(entity, viewer);
    }
    private void copy(Entity previous, Entity next) {
        if (!copyOnDeath || getExisting(previous) == null) return;
        T copied = codec.parse(RegistryOps.create(NbtOps.INSTANCE, next.registryAccess()), encode(previous).get("value")).getOrThrow();
        live.put(next, copied);
        if (persistent) next.getPersistentData().put(id.toString(), codec.encodeStart(RegistryOps.create(NbtOps.INSTANCE, next.registryAccess()), copied).getOrThrow());
    }
    public static void initialize() {
        com.kadamitas.fabricatedbackpacks.platform.network.PayloadTypeRegistry.clientboundPlay().register(
                com.kadamitas.fabricatedbackpacks.platform.network.AttachmentPayload.TYPE, com.kadamitas.fabricatedbackpacks.platform.network.AttachmentPayload.STREAM_CODEC);
        net.minecraftforge.event.entity.player.PlayerEvent.Clone.BUS.addListener(event -> TYPES.values().forEach(type -> type.copy(event.getOriginal(), event.getEntity())));
        net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent.BUS.addListener(event -> { if (event.getEntity() instanceof ServerPlayer player) TYPES.values().forEach(type -> type.synchronize(player)); });
        net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent.BUS.addListener(event -> { if (event.getEntity() instanceof ServerPlayer player) TYPES.values().forEach(type -> type.synchronize(player)); });
        net.minecraftforge.event.entity.player.PlayerEvent.StartTracking.BUS.addListener(event -> { if (event.getEntity() instanceof ServerPlayer player) TYPES.values().forEach(type -> { if (type.sync == Sync.TRACKING) type.send(event.getTarget(), player); }); });
    }
}
