package com.kadamitas.fabricatedbackpacks.platform.network;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/** Actual entity tracking membership, rather than an approximation using nearby players. */
public final class PlayerLookup {
    private static final Map<Entity, Set<ServerPlayer>> TRACKING = new WeakHashMap<>();
    private PlayerLookup() {}
    public static void start(Entity entity, ServerPlayer player) { TRACKING.computeIfAbsent(entity, ignored -> new HashSet<>()).add(player); }
    public static void stop(Entity entity, ServerPlayer player) { var players = TRACKING.get(entity); if (players != null && players.remove(player) && players.isEmpty()) TRACKING.remove(entity); }
    public static void remove(ServerPlayer player) { TRACKING.values().forEach(players -> players.remove(player)); TRACKING.values().removeIf(Set::isEmpty); }
    public static void clear(MinecraftServer server) { TRACKING.keySet().removeIf(entity -> entity.level() instanceof ServerLevel level && level.getServer() == server); }
    public static Collection<ServerPlayer> tracking(Entity entity) { return List.copyOf(TRACKING.getOrDefault(entity, Set.of())); }
    public static Collection<ServerPlayer> tracking(ServerLevel level, BlockPos pos) { return level.getChunkSource().chunkMap.getPlayers(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4), false); }
}
