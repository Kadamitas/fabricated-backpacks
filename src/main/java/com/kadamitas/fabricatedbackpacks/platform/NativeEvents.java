package com.kadamitas.fabricatedbackpacks.platform;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Shared lifecycle callbacks driven by native NeoForge events and narrowly scoped vanilla hooks. */
public final class NativeEvents {
    private NativeEvents() {}
    public static final class Event<T> {
        private final List<T> listeners = new ArrayList<>();
        public void register(T listener) { listeners.add(listener); }
        public void fire(Consumer<T> invoke) { for (T listener : List.copyOf(listeners)) invoke.accept(listener); }
    }
    @FunctionalInterface public interface Reload { void accept(MinecraftServer server, ResourceManager resources, boolean success); }
    @FunctionalInterface public interface BlockEntityCallback { void accept(BlockEntity entity, ServerLevel level); }
    @FunctionalInterface public interface ChunkLoad { void accept(ServerLevel level, LevelChunk chunk, boolean generated); }
    @FunctionalInterface public interface ChunkUnload { void accept(ServerLevel level, LevelChunk chunk); }
    @FunctionalInterface public interface LevelCallback { void accept(MinecraftServer server, ServerLevel level); }
    @FunctionalInterface public interface Death { void accept(LivingEntity entity, DamageSource source); }
    @FunctionalInterface public interface Respawn { void accept(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive); }
    @FunctionalInterface public interface PacketSender { void sendPacket(net.minecraft.network.protocol.common.custom.CustomPacketPayload packet); }
    @FunctionalInterface public interface Join { void accept(ServerGamePacketListenerImpl handler, PacketSender sender, MinecraftServer server); }
    @FunctionalInterface public interface Disconnect { void accept(ServerGamePacketListenerImpl handler, MinecraftServer server); }
    @FunctionalInterface public interface Tracking { void accept(Entity entity, ServerPlayer player); }
    @FunctionalInterface public interface CommandRegistrationCallback {
        Event<CommandRegistrationCallback> EVENT = new Event<>();
        void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context, Commands.CommandSelection selection);
    }
    public static final class ServerLifecycleEvents {
        public static final Event<Consumer<MinecraftServer>> SERVER_STARTED = new Event<>(), SERVER_STOPPED = new Event<>();
        public static final Event<Reload> END_DATA_PACK_RELOAD = new Event<>();
    }
    public static final class ServerTickEvents {
        public static final Event<Consumer<MinecraftServer>> START_SERVER_TICK = new Event<>(), END_SERVER_TICK = new Event<>();
        public static final Event<Consumer<ServerLevel>> END_LEVEL_TICK = new Event<>();
    }
    public static final class ServerBlockEntityEvents {
        public static final Event<BlockEntityCallback> BLOCK_ENTITY_LOAD = new Event<>(), BLOCK_ENTITY_UNLOAD = new Event<>();
    }
    public static final class ServerChunkEvents {
        public static final Event<ChunkLoad> CHUNK_LOAD = new Event<>();
        public static final Event<ChunkUnload> CHUNK_UNLOAD = new Event<>();
    }
    public static final class ServerLevelEvents { public static final Event<LevelCallback> LOAD = new Event<>(), UNLOAD = new Event<>(); }
    public static final class ServerLivingEntityEvents { public static final Event<Death> AFTER_DEATH = new Event<>(); }
    public static final class ServerPlayerEvents { public static final Event<Respawn> AFTER_RESPAWN = new Event<>(); }
    public static final class ServerPlayConnectionEvents {
        public static final Event<Join> JOIN = new Event<>();
        public static final Event<Disconnect> DISCONNECT = new Event<>();
    }
    public static final class EntityTrackingEvents { public static final Event<Tracking> START_TRACKING = new Event<>(), STOP_TRACKING = new Event<>(); }
    private static final java.util.Map<ServerPlayer, ServerPlayer> RESPAWN_ORIGINS = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    public static void initialize() {
        var bus = NeoForge.EVENT_BUS;
        bus.addListener((ServerStartedEvent e) -> ServerLifecycleEvents.SERVER_STARTED.fire(c -> c.accept(e.getServer())));
        bus.addListener((ServerStoppedEvent e) -> { ServerLifecycleEvents.SERVER_STOPPED.fire(c -> c.accept(e.getServer())); com.kadamitas.fabricatedbackpacks.platform.network.PlayerLookup.clear(e.getServer()); });
        bus.addListener((OnDatapackSyncEvent e) -> { if (e.getPlayer() == null) { var server = e.getPlayerList().getServer(); ServerLifecycleEvents.END_DATA_PACK_RELOAD.fire(c -> c.accept(server, server.getResourceManager(), true)); } });
        bus.addListener((RegisterCommandsEvent e) -> CommandRegistrationCallback.EVENT.fire(c -> c.register(e.getDispatcher(), e.getBuildContext(), e.getCommandSelection())));
        bus.addListener((ServerTickEvent.Pre e) -> ServerTickEvents.START_SERVER_TICK.fire(c -> c.accept(e.getServer())));
        bus.addListener((ServerTickEvent.Post e) -> ServerTickEvents.END_SERVER_TICK.fire(c -> c.accept(e.getServer())));
        bus.addListener((LevelTickEvent.Post e) -> { if (e.getLevel() instanceof ServerLevel level) ServerTickEvents.END_LEVEL_TICK.fire(c -> c.accept(level)); });
        bus.addListener((LevelEvent.Load e) -> { if (e.getLevel() instanceof ServerLevel level) ServerLevelEvents.LOAD.fire(c -> c.accept(level.getServer(), level)); });
        bus.addListener((LevelEvent.Unload e) -> { if (e.getLevel() instanceof ServerLevel level) ServerLevelEvents.UNLOAD.fire(c -> c.accept(level.getServer(), level)); });
        bus.addListener((ChunkEvent.Load e) -> { if (e.getLevel() instanceof ServerLevel level) ServerChunkEvents.CHUNK_LOAD.fire(c -> c.accept(level, e.getChunk(), e.isNewChunk())); });
        bus.addListener((ChunkEvent.Unload e) -> { if (e.getLevel() instanceof ServerLevel level) ServerChunkEvents.CHUNK_UNLOAD.fire(c -> c.accept(level, e.getChunk())); });
        // Death drops: the native drops hook fires inside die() for every living entity, including
        // players with keep-inventory (whose vanilla equipment drop is skipped by Player.dropEquipment).
        bus.addListener((LivingDropsEvent e) -> { if (e.getEntity().level() instanceof ServerLevel) ServerLivingEntityEvents.AFTER_DEATH.fire(c -> c.accept(e.getEntity(), e.getSource())); });
        bus.addListener((PlayerEvent.PlayerLoggedInEvent e) -> { if (e.getEntity() instanceof ServerPlayer p) ServerPlayConnectionEvents.JOIN.fire(c -> c.accept(p.connection, packet -> com.kadamitas.fabricatedbackpacks.platform.network.ServerPlayNetworking.send(p, packet), p.level().getServer())); });
        bus.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> { if (e.getEntity() instanceof ServerPlayer p) { ServerPlayConnectionEvents.DISCONNECT.fire(c -> c.accept(p.connection, p.level().getServer())); com.kadamitas.fabricatedbackpacks.platform.network.PlayerLookup.remove(p); } });
        // Clone fires while the new player is still being built; the respawn event fires after placement.
        bus.addListener((PlayerEvent.Clone e) -> { if (e.getEntity() instanceof ServerPlayer p && e.getOriginal() instanceof ServerPlayer previous) RESPAWN_ORIGINS.put(p, previous); });
        bus.addListener((PlayerEvent.PlayerRespawnEvent e) -> {
            if (!(e.getEntity() instanceof ServerPlayer p)) return;
            ServerPlayer previous = RESPAWN_ORIGINS.remove(p);
            ServerPlayerEvents.AFTER_RESPAWN.fire(c -> c.accept(previous == null ? p : previous, p, e.isEndConquered()));
        });
        bus.addListener((PlayerEvent.StartTracking e) -> { if (e.getEntity() instanceof ServerPlayer p) { com.kadamitas.fabricatedbackpacks.platform.network.PlayerLookup.start(e.getTarget(), p); EntityTrackingEvents.START_TRACKING.fire(c -> c.accept(e.getTarget(), p)); } });
        bus.addListener((PlayerEvent.StopTracking e) -> { if (e.getEntity() instanceof ServerPlayer p) { com.kadamitas.fabricatedbackpacks.platform.network.PlayerLookup.stop(e.getTarget(), p); EntityTrackingEvents.STOP_TRACKING.fire(c -> c.accept(e.getTarget(), p)); } });
    }
}
