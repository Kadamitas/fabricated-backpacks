package com.kadamitas.fabricatedbackpacks.automation.conduit;

import com.kadamitas.fabricatedbackpacks.automation.AutomationRegistry;
import com.kadamitas.fabricatedbackpacks.config.AutomationConfig;
import com.kadamitas.fabricatedbackpacks.config.BackpackConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.lookup.v1.block.BlockApiCache;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.TransferVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import team.reborn.energy.api.EnergyStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/** Loaded-world routing. Conduits never own transferred items, fluid or energy between transactions. */
public final class ConduitNetworks {
    private static final Logger LOGGER = LoggerFactory.getLogger("FabricatedBackpacks/Conduits");
    private static final Map<ServerLevel, WorldNetworks> WORLDS = new IdentityHashMap<>();
    private static final Set<ServerLevel> CLOSED = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean initialized;

    private ConduitNetworks() {}

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof ConduitBundleBlockEntity bundle) {
                // Fabric supplies the actual entity after inserting it into the chunk's map,
                // but before that chunk's FULL future necessarily completes. Record only the
                // supplied identity here; present()/current() defer all routing until it is ready.
                WorldNetworks world = world(level);
                if (world != null) world.track(bundle);
            }
            else if (WORLDS.containsKey(level)) WORLDS.get(level).endpointChanged(entity.getBlockPos());
        });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof ConduitBundleBlockEntity bundle) unregister(bundle);
            else if (WORLDS.containsKey(level)) WORLDS.get(level).endpointChanged(entity.getBlockPos());
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            WorldNetworks world = WORLDS.get(level);
            if (world != null) { world.unloading.add(chunk.getPos().pack()); world.chunkChanged(chunk.getPos().pack()); }
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, generated) -> {
            WorldNetworks world = WORLDS.get(level);
            if (world != null) { world.unloading.remove(chunk.getPos().pack()); world.chunkChanged(chunk.getPos().pack()); }
        });
        ServerLevelEvents.LOAD.register((server, level) -> CLOSED.remove(level));
        ServerLevelEvents.UNLOAD.register((server, level) -> { WORLDS.remove(level); CLOSED.add(level); });
        ServerTickEvents.END_LEVEL_TICK.register(ConduitNetworks::tick);
        ItemStorage.SIDED.registerForBlockEntity((bundle, side) -> side == null ? Storage.empty()
                : new ForwardStorage<>(bundle, ConduitKind.ITEM, side), AutomationRegistry.CONDUIT_BUNDLE_ENTITY);
        FluidStorage.SIDED.registerForBlockEntity((bundle, side) -> side == null ? Storage.empty()
                : new ForwardStorage<>(bundle, ConduitKind.FLUID, side), AutomationRegistry.CONDUIT_BUNDLE_ENTITY);
        EnergyStorage.SIDED.registerForBlockEntity((bundle, side) -> side == null ? EnergyStorage.EMPTY
                : new ForwardEnergy(bundle, side), AutomationRegistry.CONDUIT_BUNDLE_ENTITY);
    }

    private static AutomationConfig.Conduits limits() { return BackpackConfig.get().automation().conduits(); }
    private static WorldNetworks world(ServerLevel level) {
        return CLOSED.contains(level) ? null : WORLDS.computeIfAbsent(level, WorldNetworks::new);
    }

    public static void register(ConduitBundleBlockEntity bundle) {
        if (!(bundle.getLevel() instanceof ServerLevel level) || !bundle.current()) return;
        WorldNetworks world = world(level);
        if (world != null) world.track(bundle);
    }
    public static void unregister(ConduitBundleBlockEntity bundle) {
        if (!(bundle.getLevel() instanceof ServerLevel level)) return;
        WorldNetworks world = WORLDS.get(level);
        if (world != null && world.nodes.remove(bundle.getBlockPos().asLong(), bundle))
            for (ConduitKind kind : ConduitKind.values()) if (bundle.has(kind)) world.lanes.get(kind).invalidateAround(bundle.getBlockPos());
    }
    public static void changed(ConduitBundleBlockEntity bundle, ConduitKind kind) {
        if (!bundle.current()) return;
        register(bundle);
        if (bundle.getLevel() instanceof ServerLevel level && WORLDS.containsKey(level))
            WORLDS.get(level).lanes.get(kind).invalidateAround(bundle.getBlockPos());
    }
    public static void neighborChanged(ServerLevel level, BlockPos position) {
        WorldNetworks world = WORLDS.get(level);
        if (world != null) {
            ConduitBundleBlockEntity node = world.nodes.get(position.asLong());
            if (node != null) {
                // Link membership is owned by lane installation/mode and chunk lifecycle changes.
                // A redstone/comparator notification only needs to refresh adjacent machine slots.
                for (ConduitKind kind : ConduitKind.values()) if (node.has(kind)) world.lanes.get(kind).refreshEndpoints(position);
            } else world.endpointChanged(position);
        }
    }
    public static int networkSize(ConduitBundleBlockEntity bundle, ConduitKind kind) {
        Component component = component(bundle, kind);
        return component == null ? 0 : component.topology.size();
    }
    public static boolean oversized(ConduitBundleBlockEntity bundle, ConduitKind kind) {
        Component component = component(bundle, kind);
        return component != null && component.topology.oversized();
    }
    private static Component component(ConduitBundleBlockEntity bundle, ConduitKind kind) {
        if (!(bundle.getLevel() instanceof ServerLevel level) || !bundle.current()) return null;
        WorldNetworks world = WORLDS.get(level);
        return world == null ? null : world.lanes.get(kind).component(bundle.getBlockPos().asLong());
    }

    /** A public tick entry is also useful to embedders; repeated calls share the same live tick allowance. */
    public static void tick(ServerLevel level) {
        WorldNetworks world = WORLDS.get(level);
        if (world != null && !CLOSED.contains(level)) world.tick();
    }

    static boolean powered(ConduitBundleBlockEntity entity) {
        if (!(entity.getLevel() instanceof ServerLevel level)) return false;
        WorldNetworks world = WORLDS.get(level);
        if (world == null) return false;
        // Vanilla conductor checks can inspect two blocks away. Do not let a gate load a border chunk.
        for (int x = -2; x <= 2; x += 2) for (int z = -2; z <= 2; z += 2)
            if (!world.loaded(entity.getBlockPos().offset(x, 0, z))) return false;
        return level.hasNeighborSignal(entity.getBlockPos());
    }

    public static ConduitVisualState describe(ConduitBundleBlockEntity bundle) {
        if (!(bundle.getLevel() instanceof ServerLevel level)) return bundle.visualState();
        if (!bundle.current()) return new ConduitVisualState(bundle.installedMask(), 0, 0, 0, 0, 0);
        WorldNetworks world = world(level);
        if (world == null) return ConduitVisualState.EMPTY;
        int connections = 0, endpoints = 0, extract = 0, insert = 0, neighbors = 0;
        for (Direction side : Direction.values()) {
            BlockPos target = bundle.getBlockPos().relative(side);
            if (!world.loaded(target)) continue;
            BlockEntity entity = level.getBlockEntity(target);
            if (entity instanceof ConduitBundleBlockEntity other) neighbors |= other.installedMask() << (side.ordinal() * 3);
            for (ConduitKind kind : ConduitKind.values()) {
                if (!bundle.has(kind)) continue;
                int bit = ConduitVisualState.bit(kind, side);
                if (entity instanceof ConduitBundleBlockEntity other) {
                    if (bundle.mode(kind, side).connects() && other.has(kind) && other.mode(kind, side.getOpposite()).connects()) connections |= bit;
                } else if (!level.getBlockState(target).isAir()) {
                    Object storage = find(kind, level, target, side.getOpposite());
                    if (storage == null || !supports(storage)) continue;
                    // A disabled external port retains a wrenchable tube, but no interface plate.
                    // Routing still checks the actual mode, independently of this public geometry.
                    connections |= bit;
                    if (bundle.mode(kind, side).connects()) endpoints |= bit;
                    if (bundle.mode(kind, side).extracts()) extract |= bit;
                    if (bundle.mode(kind, side).inserts()) insert |= bit;
                }
            }
        }
        return new ConduitVisualState(bundle.installedMask(), connections, endpoints, extract, insert, neighbors);
    }

    private static Object find(ConduitKind kind, ServerLevel level, BlockPos position, Direction side) {
        return switch (kind) {
            case ITEM -> ItemStorage.SIDED.find(level, position, side);
            case FLUID -> FluidStorage.SIDED.find(level, position, side);
            case ENERGY -> EnergyStorage.SIDED.find(level, position, side);
        };
    }
    private static boolean supports(Object storage) {
        return storage instanceof EnergyStorage energy ? energy.supportsInsertion() || energy.supportsExtraction()
                : storage instanceof Storage<?> resource && (resource.supportsInsertion() || resource.supportsExtraction());
    }

    private static long limit(ConduitKind kind) {
        return switch (kind) {
            case ITEM -> limits().itemsPerOperation();
            case FLUID -> Math.multiplyExact((long) limits().fluidMbPerTick(), FluidConstants.BUCKET / 1_000);
            case ENERGY -> limits().energyPerTick();
        };
    }
    private static int interval(ConduitKind kind) { return kind == ConduitKind.ITEM ? limits().itemIntervalTicks() : 1; }

    private static final class WorldNetworks {
        final ServerLevel level;
        final Map<Long, ConduitBundleBlockEntity> nodes = new HashMap<>();
        final Set<Long> unloading = new HashSet<>();
        final EnumMap<ConduitKind, Lane> lanes = new EnumMap<>(ConduitKind.class);
        final EnumMap<ConduitKind, Map<Object, ConduitBudget>> budgets = new EnumMap<>(ConduitKind.class);
        long workTick = Long.MIN_VALUE;
        int workUsed;
        int firstComponent;
        int networkLimit;

        WorldNetworks(ServerLevel level) {
            this.level = level;
            for (ConduitKind kind : ConduitKind.values()) { lanes.put(kind, new Lane(this, kind)); budgets.put(kind, new HashMap<>()); }
        }
        void track(ConduitBundleBlockEntity bundle) {
            ConduitBundleBlockEntity previous = nodes.put(bundle.getBlockPos().asLong(), bundle);
            if (previous != bundle) for (ConduitKind kind : ConduitKind.values())
                if (bundle.has(kind) || previous != null && previous.has(kind)) lanes.get(kind).invalidateAround(bundle.getBlockPos());
        }
        boolean loaded(BlockPos position) {
            if (unloading.contains(ChunkPos.pack(position.getX() >> 4, position.getZ() >> 4))) return false;
            var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
            if (chunk == null) return false;
            if (!chunk.getBlockState(position).hasBlockEntity()) return true;
            // Capability lookup must not invoke Level's IMMEDIATE entity creation while the
            // physical machine is still being registered, or revive a removed cached endpoint.
            BlockEntity entity = chunk.getBlockEntities().get(position);
            return entity != null && !entity.isRemoved();
        }
        void invalidateAll() { for (Lane lane : lanes.values()) lane.invalidate(); }
        void endpointChanged(BlockPos position) {
            for (Direction side : Direction.values()) {
                ConduitBundleBlockEntity adjacent = nodes.get(position.relative(side).asLong());
                if (adjacent != null) for (ConduitKind kind : ConduitKind.values())
                    if (adjacent.has(kind) && adjacent.mode(kind, side.getOpposite()).connects())
                        lanes.get(kind).refreshEndpoints(adjacent.getBlockPos());
            }
        }
        void chunkChanged(long chunk) {
            EnumMap<ConduitKind, List<Long>> affected = new EnumMap<>(ConduitKind.class);
            for (ConduitKind kind : ConduitKind.values()) affected.put(kind, new ArrayList<>());
            for (ConduitBundleBlockEntity node : nodes.values()) {
                BlockPos position = node.getBlockPos();
                boolean touching = ChunkPos.pack(position.getX() >> 4, position.getZ() >> 4) == chunk;
                for (Direction side : Direction.values()) {
                    BlockPos adjacent = position.relative(side);
                    touching |= ChunkPos.pack(adjacent.getX() >> 4, adjacent.getZ() >> 4) == chunk;
                }
                if (touching) for (ConduitKind kind : ConduitKind.values()) if (node.has(kind))
                    affected.get(kind).add(position.asLong());
            }
            for (ConduitKind kind : ConduitKind.values()) lanes.get(kind).invalidate(affected.get(kind));
        }
        boolean takeWork() {
            long now = level.getGameTime();
            if (workTick != now) { workTick = now; workUsed = 0; }
            if (workUsed >= limits().maximumEndpointVisitsPerTick()) return false;
            workUsed++;
            return true;
        }
        ConduitBudget budget(ConduitKind kind, Object identity) {
            ConduitBudget result = budgets.get(kind).computeIfAbsent(identity, ignored -> new ConduitBudget());
            result.touched = level.getGameTime();
            return result;
        }
        Object identity(BlockPos position) {
            BlockEntity entity = level.getBlockEntity(position);
            return entity == null ? position : entity;
        }
        boolean automaticEnergyStorage(Object identity) {
            BlockPos position = identity instanceof BlockEntity entity ? entity.getBlockPos() : (BlockPos) identity;
            if (!loaded(position) || !identity(position).equals(identity)) return false;
            boolean receives = false, exports = false;
            // Some batteries expose separate input-only and output-only faces. Classify their
            // connected physical endpoint, not whichever single facade this route happened to find.
            for (Direction face : Direction.values()) {
                BlockPos neighbor = position.relative(face);
                ConduitBundleBlockEntity bundle = nodes.get(neighbor.asLong());
                if (bundle == null || !loaded(neighbor) || !bundle.current() || !bundle.has(ConduitKind.ENERGY)) continue;
                ConduitMode mode = bundle.mode(ConduitKind.ENERGY, face.getOpposite());
                if (!mode.connects()) continue;
                EnergyStorage storage = EnergyStorage.SIDED.find(level, position, face);
                if (storage == null) continue;
                receives |= mode.inserts() && storage.supportsInsertion();
                exports |= mode.extracts() && storage.supportsExtraction();
                if (receives && exports) return true;
            }
            return false;
        }
        void tick() {
            if (networkLimit != limits().maximumNetworkNodes()) { networkLimit = limits().maximumNetworkNodes(); invalidateAll(); }
            for (Lane lane : lanes.values()) lane.advance(limits().maximumEndpointVisitsPerTick());
            List<Component> active = new ArrayList<>();
            for (Lane lane : lanes.values()) for (Component component : lane.components.values())
                if (component.live() && !component.sources.isEmpty() && !component.destinations.isEmpty()) active.add(component);
            active.sort(java.util.Comparator.comparingInt((Component value) -> value.lane.kind.ordinal())
                    .thenComparingLong(value -> value.topology.nodes().getFirst()));
            // Do not burn the whole allowance by repeatedly retrying capped or empty sources.
            // Later API producers share this tick's work budget and must retain its unused portion.
            int attempts = active.stream().mapToInt(component -> component.sources.size()).sum();
            while (!active.isEmpty() && attempts-- > 0 && takeWork()) {
                firstComponent = Math.floorMod(firstComponent, active.size());
                Component component = active.get(firstComponent);
                firstComponent = (firstComponent + 1) % active.size();
                component.attempt();
            }
            if (level.getGameTime() % 200 == 0) {
                long oldest = level.getGameTime() - Math.max(1_200, limits().itemIntervalTicks());
                for (Map<Object, ConduitBudget> values : budgets.values()) values.values().removeIf(value -> value.touched < oldest);
            }
        }
    }

    private static final class Lane {
        final WorldNetworks world;
        final ConduitKind kind;
        final Map<ConduitTopology.Component, Component> components = new IdentityHashMap<>();
        long generation;
        ConduitTopology topology;

        Lane(WorldNetworks world, ConduitKind kind) { this.world = world; this.kind = kind; }
        void invalidate() { generation++; topology = null; components.clear(); }
        void invalidateAround(BlockPos position) {
            List<Long> affected = new ArrayList<>(7);
            affected.add(position.asLong());
            for (Direction side : Direction.values()) affected.add(position.relative(side).asLong());
            invalidate(affected);
        }
        void invalidate(List<Long> affected) {
            if (topology == null || affected.isEmpty()) return;
            for (ConduitTopology.Component previous : topology.invalidate(affected)) {
                Component component = components.remove(previous);
                if (component != null) component.invalidated = true;
            }
        }
        void refreshEndpoints(BlockPos position) {
            Component component = component(position.asLong());
            if (component != null && component.live()) component.refreshEndpoints(position.asLong());
        }
        boolean present(long position) {
            ConduitBundleBlockEntity entity = world.nodes.get(position);
            return entity != null && entity.has(kind) && world.loaded(entity.getBlockPos()) && entity.current();
        }
        long[] adjacent(long position) {
            ConduitBundleBlockEntity entity = world.nodes.get(position);
            if (entity == null) return new long[0];
            long[] result = new long[6];
            int count = 0;
            for (Direction side : Direction.values()) {
                long neighbor = entity.getBlockPos().relative(side).asLong();
                if (entity.mode(kind, side).connects() && present(neighbor)
                        && world.nodes.get(neighbor).mode(kind, side.getOpposite()).connects()) result[count++] = neighbor;
            }
            return java.util.Arrays.copyOf(result, count);
        }
        void advance(int work) {
            if (topology == null) {
                List<Long> roots = world.nodes.keySet().stream().filter(this::present).sorted().toList();
                topology = new ConduitTopology(roots, limits().maximumNetworkNodes(), this::present, this::adjacent);
            }
            if (!topology.complete()) for (ConduitTopology.Component discovered : topology.advance(work))
                components.put(discovered, new Component(this, discovered, generation));
        }
        Component component(long position) { return topology == null ? null : components.get(topology.component(position)); }
    }

    private static final class Endpoint {
        final Component component;
        final ConduitBundleBlockEntity conduit;
        final Direction side;
        final BlockPos position;
        final long installedGeneration;
        final BlockApiCache<?, Direction> cache;
        Iterator<? extends StorageView<?>> scan;
        Object scanIdentity;
        Storage<?> scanStorage;
        StorageView<?> pending;
        int attemptedTargets;
        int slotCursor;
        int pendingSlot = -1;

        Endpoint(Component component, ConduitBundleBlockEntity conduit, Direction side) {
            this.component = component;
            this.conduit = conduit;
            this.side = side;
            position = conduit.getBlockPos().relative(side);
            installedGeneration = conduit.laneGeneration(component.lane.kind);
            ServerLevel level = component.lane.world.level;
            cache = switch (component.lane.kind) {
                case ITEM -> BlockApiCache.create(ItemStorage.SIDED, level, position);
                case FLUID -> BlockApiCache.create(FluidStorage.SIDED, level, position);
                case ENERGY -> BlockApiCache.create(EnergyStorage.SIDED, level, position);
            };
        }
        boolean current() {
            return component.live() && conduit.current() && conduit.has(component.lane.kind)
                    && conduit.laneGeneration(component.lane.kind) == installedGeneration
                    && component.lane.world.loaded(position)
                    && !(component.lane.world.level.getBlockEntity(position) instanceof ConduitBundleBlockEntity);
        }
        Located locate() {
            if (!current()) return null;
            WorldNetworks world = component.lane.world;
            BlockState state = world.level.getBlockState(position);
            if (state.isAir()) return null;
            BlockEntity entity = world.level.getBlockEntity(position);
            Object storage = cache.find(state, side.getOpposite());
            return storage == null ? null : new Located(this, storage, entity, state);
        }
        StorageView<?> nextView(Storage<?> storage, Object identity, Predicate<TransferVariant<?>> accepts) {
            if (!java.util.Objects.equals(scanIdentity, identity)) {
                scan = null; scanStorage = null; pending = null; pendingSlot = -1; slotCursor = 0; attemptedTargets = 0;
            }
            scanIdentity = identity;
            if (storage instanceof SlottedStorage<?> slots) {
                // A fresh wrapper may be returned at every lookup. Retain only the slot ordinal,
                // never an old provider's view, and use direct indexing for bounded high-slot scans.
                scan = null;
                scanStorage = storage;
                pending = null;
                int count = slots.getSlotCount();
                if (count == 0) { pendingSlot = -1; return null; }
                if (pendingSlot >= 0 && pendingSlot < count) {
                    StorageView<?> view = slots.getSlot(pendingSlot);
                    if (eligible(view, accepts)) return view;
                }
                pendingSlot = -1;
                attemptedTargets = 0;
                for (int checked = 0; checked < Math.min(64, count); checked++) {
                    int index = Math.floorMod(slotCursor, count);
                    slotCursor = (index + 1) % count;
                    StorageView<?> view = slots.getSlot(index);
                    if (eligible(view, accepts)) { pendingSlot = index; return view; }
                }
                return null;
            }
            if (scanStorage != storage) {
                scan = null; pending = null; pendingSlot = -1; attemptedTargets = 0;
                scanStorage = storage;
            }
            if (pending != null && eligible(pending, accepts)) return pending;
            if (scan == null || !scan.hasNext()) scan = storage.iterator();
            attemptedTargets = 0;
            for (int checked = 0; checked < 64 && scan.hasNext(); checked++) {
                pending = scan.next();
                if (eligible(pending, accepts)) return pending;
            }
            pending = null;
            return null;
        }
        private boolean eligible(StorageView<?> view, Predicate<TransferVariant<?>> accepts) {
            return !view.isResourceBlank() && view.getAmount() > 0
                    && view.getResource() instanceof TransferVariant<?> resource && accepts.test(resource);
        }
        void triedView(boolean moved) {
            if (moved || ++attemptedTargets >= component.destinations.size()) { pending = null; pendingSlot = -1; attemptedTargets = 0; }
        }
    }

    private record Located(Endpoint endpoint, Object storage, BlockEntity entity, BlockState state) {
        Object identity() { return entity == null ? endpoint.position : entity; }
        boolean current() {
            if (!endpoint.current()) return false;
            ServerLevel level = endpoint.component.lane.world.level;
            return level.getBlockEntity(endpoint.position) == entity && (entity != null || level.getBlockState(endpoint.position) == state);
        }
    }

    private static final class Cursor extends SnapshotParticipant<Integer> {
        int next;
        int choose(int size, TransactionContext transaction) {
            if (size < 1) throw new IllegalArgumentException("Empty route list");
            updateSnapshots(transaction);
            int chosen = Math.floorMod(next, size);
            next = (chosen + 1) % size;
            return chosen;
        }
        @Override protected Integer createSnapshot() { return next; }
        @Override protected void readSnapshot(Integer value) { next = value; }
    }

    private static final class Component {
        final Lane lane;
        final ConduitTopology.Component topology;
        final long generation;
        final List<Endpoint> sources = new ArrayList<>();
        final List<Endpoint> destinations = new ArrayList<>();
        final Map<Long, Endpoint[]> endpointsByNode = new HashMap<>();
        final Map<Object, Cursor> destinationsBySource = new HashMap<>();
        int sourceIndex;
        boolean routing;
        boolean invalidated;
        long lastFailure = Long.MIN_VALUE;

        Component(Lane lane, ConduitTopology.Component topology, long generation) {
            this.lane = lane;
            this.topology = topology;
            this.generation = generation;
            if (topology.oversized()) return;
            for (long node : topology.nodes()) refreshEndpoints(node);
        }
        boolean live() { return !invalidated && lane.generation == generation && !topology.oversized() && !CLOSED.contains(lane.world.level); }
        void refreshEndpoints(long node) {
            ConduitBundleBlockEntity entity = lane.world.nodes.get(node);
            if (entity == null) return;
            Endpoint[] endpoints = endpointsByNode.computeIfAbsent(node, ignored -> new Endpoint[6]);
            for (Direction side : Direction.values()) {
                BlockPos target = entity.getBlockPos().relative(side);
                boolean present = entity.mode(lane.kind, side).connects() && lane.world.loaded(target)
                        && !lane.world.level.getBlockState(target).isAir()
                        && !(lane.world.level.getBlockEntity(target) instanceof ConduitBundleBlockEntity);
                Endpoint endpoint = endpoints[side.ordinal()];
                if (!present && endpoint != null) {
                    sources.remove(endpoint); destinations.remove(endpoint); endpoints[side.ordinal()] = null;
                } else if (present && endpoint == null) {
                    endpoint = new Endpoint(this, entity, side);
                    endpoints[side.ordinal()] = endpoint;
                    if (entity.mode(lane.kind, side).extracts()) sources.add(endpoint);
                    if (entity.mode(lane.kind, side).inserts()) destinations.add(endpoint);
                }
            }
            pruneSourceCursors();
        }
        private void pruneSourceCursors() {
            // Endpoint refresh deliberately preserves this component. Do not let that retain every
            // replaced machine forever, or reset the destination cursor of a still-live source.
            destinationsBySource.keySet().removeIf(source -> {
                BlockPos position = source instanceof BlockEntity blockEntity ? blockEntity.getBlockPos() : (BlockPos) source;
                if (!lane.world.loaded(position)) return true;
                BlockEntity current = lane.world.level.getBlockEntity(position);
                return source instanceof BlockEntity previous ? previous.isRemoved() || current != previous
                        : current != null || lane.world.level.getBlockState(position).isAir();
            });
        }
        void attempt() {
            if (!live() || routing || sources.isEmpty() || destinations.isEmpty()) return;
            sourceIndex = Math.floorMod(sourceIndex, sources.size());
            Endpoint source = sources.get(sourceIndex);
            sourceIndex = (sourceIndex + 1) % sources.size();
            if (!source.current() || !source.conduit.extracts(lane.kind, source.side)) return;
            Located located = source.locate();
            if (located == null) return;
            long now = lane.world.level.getGameTime();
            ConduitBudget sourceBudget = lane.world.budget(lane.kind, located.identity());
            long maximum = sourceBudget.available(now, limit(lane.kind), interval(lane.kind));
            if (maximum == 0 || sourceBudget.receivedThisTick(now)) return;
            routing = true;
            try (Transaction transaction = Transaction.openOuter()) {
                if (lane.kind == ConduitKind.ENERGY && located.storage instanceof EnergyStorage energy && energy.supportsExtraction())
                    energy(located, energy, maximum, transaction);
                else if (located.storage instanceof Storage<?> storage && storage.supportsExtraction())
                    resource(located, storage, maximum, transaction);
                if (live()) transaction.commit();
            } catch (RuntimeException failure) { failure(failure); }
            finally { routing = false; }
        }
        private Located target(Object source, TransactionContext transaction) {
            if (destinations.isEmpty()) return null;
            Cursor cursor = destinationsBySource.computeIfAbsent(source, ignored -> new Cursor());
            Endpoint endpoint = destinations.get(cursor.choose(destinations.size(), transaction));
            if (!endpoint.conduit.mode(lane.kind, endpoint.side).inserts()) return null;
            return endpoint.locate();
        }
        private long maximum(Object sourceIdentity, Located target, long requested) {
            if (target == null || sourceIdentity.equals(target.identity())) return 0;
            long now = lane.world.level.getGameTime();
            return Math.min(requested, lane.world.budget(lane.kind, target.identity()).available(now, limit(lane.kind), interval(lane.kind)));
        }
        private void charge(Object source, Object target, long amount, TransactionContext transaction) {
            if (amount == 0) return;
            long now = lane.world.level.getGameTime();
            lane.world.budget(lane.kind, source).charge(now, amount, interval(lane.kind), false, transaction);
            lane.world.budget(lane.kind, target).charge(now, amount, interval(lane.kind), true, transaction);
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        private void resource(Located source, Storage<?> sourceStorage, long requested, TransactionContext transaction) {
            Located target = target(source.identity(), transaction);
            long maximum = maximum(source.identity(), target, requested);
            if (maximum == 0 || !(target.storage instanceof Storage<?> storage) || !storage.supportsInsertion()) return;
            // Filter while scanning, not after selecting the first resource. Fresh wrapper providers
            // must not repeatedly return a denied first slot and hide allowed later items or tanks.
            StorageView<?> view = source.endpoint.nextView(sourceStorage, source.identity(), resource ->
                    source.endpoint.conduit.accepts(lane.kind, source.endpoint.side, resource)
                            && target.endpoint.conduit.accepts(lane.kind, target.endpoint.side, resource));
            if (view == null) return;
            TransferVariant<?> resource = (TransferVariant<?>) view.getResource();
            long moved = ConduitTransfers.move((StorageView) view, (Storage) storage, resource, maximum,
                    () -> source.current() && source.endpoint.conduit.extracts(lane.kind, source.endpoint.side)
                            && source.endpoint.conduit.accepts(lane.kind, source.endpoint.side, resource)
                            && acceptsTarget(target, resource), transaction);
            charge(source.identity(), target.identity(), moved, transaction);
            source.endpoint.triedView(moved > 0);
        }
        private boolean acceptsTarget(Located target, TransferVariant<?> resource) {
            return target.current() && target.endpoint.conduit.mode(lane.kind, target.endpoint.side).inserts()
                    && target.endpoint.conduit.accepts(lane.kind, target.endpoint.side, resource);
        }
        private void energy(Located source, EnergyStorage storage, long requested, TransactionContext transaction) {
            Located target = target(source.identity(), transaction);
            long maximum = maximum(source.identity(), target, requested);
            if (maximum == 0 || !(target.storage instanceof EnergyStorage sink) || !sink.supportsInsertion()) return;
            // Two automatic storage ports must not shuttle charge back and forth. Explicit EXTRACT/INSERT modes opt in.
            if (source.endpoint.conduit.mode(lane.kind, source.endpoint.side) == ConduitMode.BOTH
                    && target.endpoint.conduit.mode(lane.kind, target.endpoint.side) == ConduitMode.BOTH
                    && lane.world.automaticEnergyStorage(source.identity()) && lane.world.automaticEnergyStorage(target.identity())) return;
            long moved = ConduitTransfers.move(storage, sink, maximum, () -> source.current() && target.current(), transaction);
            charge(source.identity(), target.identity(), moved, transaction);
        }
        private void failure(RuntimeException failure) {
            long now = lane.world.level.getGameTime();
            if (lastFailure == Long.MIN_VALUE || now - lastFailure >= 200) {
                LOGGER.warn("Aborted {} conduit transfer near {}", lane.kind, BlockPos.of(topology.nodes().getFirst()), failure);
                lastFailure = now;
            }
        }
    }

    private abstract static class ForwardPort {
        final ConduitBundleBlockEntity entity;
        final ConduitKind kind;
        final Direction side;
        final long generation;

        ForwardPort(ConduitBundleBlockEntity entity, ConduitKind kind, Direction side) {
            this.entity = entity;
            this.kind = kind;
            this.side = side;
            generation = entity.laneGeneration(kind);
        }
        boolean current() {
            return entity.current() && entity.has(kind) && entity.laneGeneration(kind) == generation
                    && entity.extracts(kind, side);
        }
        boolean advertisedInsertion() {
            if (entity.getLevel() != null && entity.getLevel().isClientSide()) {
                return !entity.isRemoved() && entity.has(kind) && entity.mode(kind, side).extracts()
                        && entity.getLevel().hasChunkAt(entity.getBlockPos())
                        && entity.getLevel().getBlockEntity(entity.getBlockPos()) == entity;
            }
            return current();
        }
        Component route() {
            Component component = component(entity, kind);
            return current() && component != null && component.live() && !component.routing && !component.destinations.isEmpty() ? component : null;
        }
        Object sourceIdentity(Component component) {
            BlockPos source = entity.getBlockPos().relative(side);
            if (!component.lane.world.loaded(source) || component.lane.world.level.getBlockEntity(source) instanceof ConduitBundleBlockEntity) return null;
            return component.lane.world.identity(source);
        }
        boolean sourceCurrent(Component component, Object identity) {
            BlockPos source = entity.getBlockPos().relative(side);
            return current() && component.live() && component.lane.world.loaded(source)
                    && component.lane.world.identity(source).equals(identity);
        }
        long allowance(Component component, Object identity, long maximum) {
            long now = component.lane.world.level.getGameTime();
            ConduitBudget budget = component.lane.world.budget(kind, identity);
            return budget.receivedThisTick(now) ? 0 : Math.min(maximum, budget.available(now, limit(kind), interval(kind)));
        }
    }

    private static final class ForwardStorage<T extends TransferVariant<?>> extends ForwardPort implements Storage<T> {
        ForwardStorage(ConduitBundleBlockEntity entity, ConduitKind kind, Direction side) { super(entity, kind, side); }
        @Override public boolean supportsInsertion() { return advertisedInsertion(); }
        @Override public boolean supportsExtraction() { return false; }
        @Override public long extract(T resource, long maximum, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            return 0;
        }
        @Override public Iterator<StorageView<T>> iterator() { return Collections.emptyIterator(); }
        @Override @SuppressWarnings("unchecked") public long insert(T resource, long maximum, TransactionContext parent) {
            StoragePreconditions.notBlankNotNegative(resource, maximum);
            Component component = route();
            if (maximum == 0 || component == null || !entity.accepts(kind, side, resource)) return 0;
            Object source = sourceIdentity(component);
            if (source == null) return 0;
            long offered = allowance(component, source, maximum);
            if (offered == 0) return 0;
            component.routing = true;
            try (Transaction transaction = parent.openNested()) {
                long inserted = 0;
                List<Located> acceptedTargets = new ArrayList<>();
                for (int attempt = 0; attempt < component.destinations.size() && inserted < offered && component.lane.world.takeWork(); attempt++) {
                    if (!sourceCurrent(component, source) || !entity.accepts(kind, side, resource)) return 0;
                    Located target = component.target(source, transaction);
                    long request = component.maximum(source, target, offered - inserted);
                    if (request == 0 || !(target.storage instanceof Storage<?> storage) || !storage.supportsInsertion()
                            || !component.acceptsTarget(target, resource)) continue;
                    long accepted = ((Storage<T>) storage).insert(resource, request, transaction);
                    if (accepted < 0 || accepted > request || !component.acceptsTarget(target, resource)
                            || !sourceCurrent(component, source) || !entity.accepts(kind, side, resource)) return 0;
                    component.charge(source, target.identity(), accepted, transaction);
                    if (accepted > 0) acceptedTargets.add(target);
                    inserted += accepted;
                }
                // A later destination callback may edit a policy on an earlier accepted destination.
                if (sourceCurrent(component, source) && entity.accepts(kind, side, resource)
                        && acceptedTargets.stream().allMatch(target -> component.acceptsTarget(target, resource))) {
                    transaction.commit();
                    return inserted;
                }
                return 0;
            } catch (RuntimeException failure) { component.failure(failure); return 0; }
            finally { component.routing = false; }
        }
    }

    private static final class ForwardEnergy extends ForwardPort implements EnergyStorage {
        ForwardEnergy(ConduitBundleBlockEntity entity, Direction side) { super(entity, ConduitKind.ENERGY, side); }
        @Override public boolean supportsInsertion() { return advertisedInsertion(); }
        @Override public boolean supportsExtraction() { return false; }
        @Override public long getAmount() { return 0; }
        @Override public long getCapacity() { return current() ? limit(kind) : 0; }
        @Override public long extract(long maximum, TransactionContext transaction) {
            if (maximum < 0) throw new IllegalArgumentException("Negative energy extraction");
            return 0;
        }
        @Override public long insert(long maximum, TransactionContext parent) {
            if (maximum < 0) throw new IllegalArgumentException("Negative energy insertion");
            Component component = route();
            if (maximum == 0 || component == null) return 0;
            Object source = sourceIdentity(component);
            if (source == null) return 0;
            long offered = allowance(component, source, maximum);
            if (offered == 0) return 0;
            boolean automaticStorage = entity.mode(kind, side) == ConduitMode.BOTH
                    && component.lane.world.automaticEnergyStorage(source);
            component.routing = true;
            try (Transaction transaction = parent.openNested()) {
                long inserted = 0;
                for (int attempt = 0; attempt < component.destinations.size() && inserted < offered && component.lane.world.takeWork(); attempt++) {
                    Located target = component.target(source, transaction);
                    long request = component.maximum(source, target, offered - inserted);
                    if (request == 0 || !(target.storage instanceof EnergyStorage storage) || !storage.supportsInsertion()) continue;
                    if (automaticStorage && target.endpoint.conduit.mode(kind, target.endpoint.side) == ConduitMode.BOTH
                            && component.lane.world.automaticEnergyStorage(target.identity())) continue;
                    long accepted = storage.insert(request, transaction);
                    if (accepted < 0 || accepted > request || !target.current() || !sourceCurrent(component, source)) return 0;
                    component.charge(source, target.identity(), accepted, transaction);
                    inserted += accepted;
                }
                if (sourceCurrent(component, source)) { transaction.commit(); return inserted; }
                return 0;
            } catch (RuntimeException failure) { component.failure(failure); return 0; }
            finally { component.routing = false; }
        }
    }
}
