package com.kadamitas.fabricatedbackpacks.automation.conduit;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** One geometry definition for the chunk mesh, collision outline and individual-lane selection. */
public final class ConduitGeometry {
    public enum Role { TUBE, HUB, COLLAR, ENDPOINT }
    /** Block-unit bounds. Side is the owning branch direction; only central hubs have no side. */
    public record Part(ConduitKind kind, Role role, Direction side, AABB bounds) {}
    private record Geometry(List<Part> parts, VoxelShape shape) {}
    private static final int CACHE_LIMIT = 512;
    private static final double HIT_TOLERANCE = 1.0E-6;
    private static final Map<ConduitVisualState, Geometry> CACHE = new LinkedHashMap<>(64, .75F, true);

    private ConduitGeometry() {}

    public static List<Part> parts(ConduitVisualState state) { return geometry(state).parts(); }
    public static VoxelShape shape(ConduitVisualState state) { return geometry(state).shape(); }

    /** Render-only omission: a whole outer tube cap is already painted by its opaque terminal fitting. */
    public static boolean coveredTubeCap(Part tube, Direction face, List<Part> parts) {
        if (tube.role() != Role.TUBE || tube.side() != face) return false;
        AABB bounds = tube.bounds();
        boolean positive = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double edge = positive ? 1 : 0;
        double cap = positive ? face.getAxis().choose(bounds.maxX, bounds.maxY, bounds.maxZ)
                : face.getAxis().choose(bounds.minX, bounds.minY, bounds.minZ);
        if (cap != edge) return false;
        for (Part part : parts) {
            if (part.kind() != tube.kind() || part.side() != face
                    || part.role() != Role.COLLAR && part.role() != Role.ENDPOINT) continue;
            AABB fitting = part.bounds();
            double outer = positive ? face.getAxis().choose(fitting.maxX, fitting.maxY, fitting.maxZ)
                    : face.getAxis().choose(fitting.minX, fitting.minY, fitting.minZ);
            if (outer != edge) continue;
            boolean covers = true;
            for (Direction.Axis axis : Direction.Axis.values()) if (axis != face.getAxis()) {
                covers &= axis.choose(fitting.minX, fitting.minY, fitting.minZ)
                        <= axis.choose(bounds.minX, bounds.minY, bounds.minZ)
                        && axis.choose(fitting.maxX, fitting.maxY, fitting.maxZ)
                        >= axis.choose(bounds.maxX, bounds.maxY, bounds.maxZ);
            }
            if (covers) return true;
        }
        return false;
    }

    /** Both ends of a connection use the same world-axis lane coordinates, never mirrored face bases. */
    public static Vec3 faceCenter(ConduitVisualState state, ConduitKind kind, Direction side) {
        double lane = lane(state.faceLayoutMask(side), kind);
        double[] point = {lane, lane, lane};
        point[axis(side)] = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 16 : 0;
        return new Vec3(point[0] / 16, point[1] / 16, point[2] / 16);
    }

    public static Optional<ConduitKind> hitKind(ConduitVisualState state, Vec3 localHit, Direction face) {
        return hitPart(state, localHit, face).map(Part::kind);
    }

    /** Select the physical surface hit, never a nearest lane elsewhere in this block. */
    public static Optional<Part> hitPart(ConduitVisualState state, Vec3 localHit, Direction face) {
        if (!Double.isFinite(localHit.x) || !Double.isFinite(localHit.y) || !Double.isFinite(localHit.z))
            return Optional.empty();
        List<Part> parts = parts(state);
        Part selected = null;
        int priority = -1;
        double nearest = Double.POSITIVE_INFINITY;
        for (Part part : parts) {
            AABB bounds = part.bounds();
            if (!contains(bounds, localHit, HIT_TOLERANCE)) continue;
            double surface = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                    ? face.getAxis().choose(bounds.maxX, bounds.maxY, bounds.maxZ)
                    : face.getAxis().choose(bounds.minX, bounds.minY, bounds.minZ);
            double distance = Math.abs(face.getAxis().choose(localHit.x, localHit.y, localHit.z) - surface);
            if (distance > HIT_TOLERANCE) continue;
            int rank = switch (part.role()) { case ENDPOINT -> 3; case COLLAR -> 2; case TUBE -> 1; case HUB -> 0; };
            if (rank > priority || rank == priority && distance < nearest) {
                selected = part;
                priority = rank;
                nearest = distance;
            }
        }
        if (selected == null) return Optional.empty();
        // A cuboid face buried inside another part is not an exposed interface.
        Vec3 outside = localHit.add(face.getStepX() * HIT_TOLERANCE * 4,
                face.getStepY() * HIT_TOLERANCE * 4, face.getStepZ() * HIT_TOLERANCE * 4);
        for (Part part : parts) if (contains(part.bounds(), outside, -HIT_TOLERANCE)) return Optional.empty();
        return Optional.of(selected);
    }

    private static boolean contains(AABB bounds, Vec3 point, double tolerance) {
        return point.x >= bounds.minX - tolerance && point.x <= bounds.maxX + tolerance
                && point.y >= bounds.minY - tolerance && point.y <= bounds.maxY + tolerance
                && point.z >= bounds.minZ - tolerance && point.z <= bounds.maxZ + tolerance;
    }

    private static synchronized Geometry geometry(ConduitVisualState state) {
        Geometry found = CACHE.get(state);
        if (found != null) return found;
        ArrayList<Part> parts = new ArrayList<>();
        boolean single = Integer.bitCount(state.installedMask()) == 1;
        for (ConduitKind kind : ConduitKind.values()) if (state.has(kind)) {
            double center = lane(state.installedMask(), kind);
            double radius = single ? 1.25 : 1;
            double hub = single ? 1.75 : 1.25;
            add(parts, kind, Role.HUB, null, new double[]{center - hub, center - hub, center - hub},
                    new double[]{center + hub, center + hub, center + hub});
            for (Direction side : Direction.values()) if (state.connected(kind, side)) {
                int axis = axis(side);
                double edge = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 16 : 0;
                double face = lane(state.faceLayoutMask(side), kind);
                double[] start = {center, center, center};
                double[] end = {face, face, face};
                end[axis] = edge;
                if (center == face) segment(parts, kind, side, start, end, radius);
                else {
                    double[] current = start.clone();
                    double[] next = current.clone();
                    next[axis] = edge == 16 ? 12.5 : 3.5;
                    segment(parts, kind, side, current, next, radius);
                    current = next;
                    for (int transverse = 0; transverse < 3; transverse++) if (transverse != axis) {
                        double[] low = current.clone(), high = current.clone();
                        for (int coordinate = 0; coordinate < 3; coordinate++) { low[coordinate] -= 1.5; high[coordinate] += 1.5; }
                        add(parts, kind, Role.COLLAR, side, low, high);
                        next = current.clone(); next[transverse] = face;
                        segment(parts, kind, side, current, next, radius);
                        current = next;
                    }
                    double[] low = current.clone(), high = current.clone();
                    for (int coordinate = 0; coordinate < 3; coordinate++) { low[coordinate] -= 1.5; high[coordinate] += 1.5; }
                    add(parts, kind, Role.COLLAR, side, low, high);
                    segment(parts, kind, side, current, end, radius);
                }
                // Disabled external ports expose only their thin, wrenchable tube.
                if (!state.endpoint(kind, side) && (state.neighborMask(side) & kind.mask()) == 0) continue;
                double collar = Integer.bitCount(state.faceLayoutMask(side)) == 1 ? 1.75 : 1.375;
                double[] low = {face - collar, face - collar, face - collar};
                double[] high = {face + collar, face + collar, face + collar};
                low[axis] = edge == 16 ? 14.75 : 0;
                high[axis] = edge == 16 ? 16 : 1.25;
                add(parts, kind, state.endpoint(kind, side) ? Role.ENDPOINT : Role.COLLAR, side, low, high);
            }
        }
        VoxelShape shape = Shapes.empty();
        for (Part part : parts) {
            AABB b = part.bounds();
            shape = Shapes.or(shape, Shapes.box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ));
        }
        Geometry result = new Geometry(List.copyOf(parts), shape.optimize());
        if (CACHE.size() >= CACHE_LIMIT) CACHE.remove(CACHE.keySet().iterator().next());
        CACHE.put(state, result);
        return result;
    }

    private static double lane(int mask, ConduitKind kind) { return Integer.bitCount(mask) <= 1 ? 8 : 5 + kind.ordinal() * 3; }
    private static int axis(Direction side) { return switch (side.getAxis()) { case X -> 0; case Y -> 1; case Z -> 2; }; }
    private static void segment(List<Part> parts, ConduitKind kind, Direction side, double[] from, double[] to, double radius) {
        double[] low = new double[3], high = new double[3];
        boolean length = false;
        for (int axis = 0; axis < 3; axis++) {
            low[axis] = Math.max(0, Math.min(from[axis], to[axis]) - radius);
            high[axis] = Math.min(16, Math.max(from[axis], to[axis]) + radius);
            length |= from[axis] != to[axis];
        }
        if (length) add(parts, kind, Role.TUBE, side, low, high);
    }
    private static void add(List<Part> parts, ConduitKind kind, Role role, Direction side, double[] low, double[] high) {
        parts.add(new Part(kind, role, side, new AABB(low[0] / 16, low[1] / 16, low[2] / 16,
                high[0] / 16, high[1] / 16, high[2] / 16)));
    }
}
