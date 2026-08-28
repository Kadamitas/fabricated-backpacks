package com.kadamitas.fabricatedbackpacks.assets;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitGeometry;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitVisualState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConduitGeometryTest {
    @Test void snapshotsRejectAbsentLaneFlagsAndExposeImmutableCachedGeometry() {
        var state = new ConduitVisualState(9, -1, -1, -1, -1, -1);
        assertEquals(1, state.installedMask());
        assertEquals(63, state.connectionMask(ConduitKind.ITEM));
        assertEquals(0, state.connectionMask(ConduitKind.FLUID));
        assertEquals(state.connectionBits(), state.endpointBits());
        assertEquals((1 << 18) - 1, state.neighborBits());
        var parts = ConduitGeometry.parts(state);
        assertSame(parts, ConduitGeometry.parts(state));
        assertThrows(UnsupportedOperationException.class, () -> parts.clear());
        assertTrue(ConduitGeometry.shape(ConduitVisualState.EMPTY).isEmpty());
        assertTrue(ConduitGeometry.hitKind(ConduitVisualState.EMPTY, Vec3.ZERO, Direction.NORTH).isEmpty());
    }

    @Test void allOccupanciesAndJunctionsHaveBoundedSeparatedPhysicalLanes() {
        for (int installed = 1; installed < 8; installed++) for (int faces = 0; faces < 64; faces++) {
            int bits = 0;
            for (Direction face : Direction.values()) if ((faces & (1 << face.ordinal())) != 0)
                bits |= installed << (face.ordinal() * 3);
            var state = new ConduitVisualState(installed, bits, bits, bits, 0, 0);
            var parts = ConduitGeometry.parts(state);
            assertFalse(parts.isEmpty());
            assertFalse(ConduitGeometry.shape(state).isEmpty());
            for (var part : parts) {
                AABB b = part.bounds();
                assertTrue(b.minX >= 0 && b.minY >= 0 && b.minZ >= 0 && b.maxX <= 1 && b.maxY <= 1 && b.maxZ <= 1);
                assertTrue(b.minX < b.maxX && b.minY < b.maxY && b.minZ < b.maxZ);
                assertTrue(state.has(part.kind()));
                for (var other : parts) if (other.kind() != part.kind())
                    assertFalse(b.intersects(other.bounds()), "Different lanes cannot intersect: " + state);
            }
        }
    }

    @Test void everySharedFaceMatchesAcrossSingleBundleAndMixedBundleTransitions() {
        for (int ours = 1; ours < 8; ours++) for (int theirs = 1; theirs < 8; theirs++)
            for (Direction face : Direction.values()) for (ConduitKind kind : ConduitKind.values()) {
                if ((ours & theirs & kind.mask()) == 0) continue;
                var a = new ConduitVisualState(ours, ConduitVisualState.bit(kind, face), 0, 0, 0, theirs << (face.ordinal() * 3));
                var b = new ConduitVisualState(theirs, ConduitVisualState.bit(kind, face.getOpposite()), 0, 0, 0,
                        ours << (face.getOpposite().ordinal() * 3));
                Vec3 here = ConduitGeometry.faceCenter(a, kind, face);
                Vec3 there = ConduitGeometry.faceCenter(b, kind, face.getOpposite())
                        .add(face.getStepX(), face.getStepY(), face.getStepZ());
                assertEquals(here, there, "Shared face must use world-axis coordinates");
                assertEquals(kind, ConduitGeometry.hitKind(a, here, face).orElseThrow());
                assertEquals(kind, ConduitGeometry.hitKind(b, there.subtract(face.getStepX(), face.getStepY(), face.getStepZ()), face.getOpposite()).orElseThrow());
                assertTrue(ConduitGeometry.parts(a).stream().anyMatch(part -> part.kind() == kind && part.side() == face
                        && part.bounds().inflate(1e-9).contains(here)), "Collar must cover the shared anchor");
            }
    }

    @Test void singlePipesAreCenteredAndEachParallelLaneCanBeSelected() {
        for (ConduitKind kind : ConduitKind.values()) for (Direction face : Direction.values()) {
            var single = new ConduitVisualState(kind.mask(), ConduitVisualState.bit(kind, face), 0, 0, 0, 0);
            Vec3 point = ConduitGeometry.faceCenter(single, kind, face);
            for (Direction.Axis axis : Direction.Axis.values()) if (axis != face.getAxis())
                assertEquals(.5, axis.choose(point.x, point.y, point.z));
        }
        for (Direction face : Direction.values()) {
            int bits = 7 << (face.ordinal() * 3);
            var bundle = new ConduitVisualState(7, bits, bits, bits, bits, 0);
            for (ConduitKind kind : ConduitKind.values()) {
                Vec3 point = ConduitGeometry.faceCenter(bundle, kind, face);
                assertEquals(kind, ConduitGeometry.hitKind(bundle, point, face).orElseThrow());
                assertTrue(bundle.extracting(kind, face));
                assertTrue(bundle.inserting(kind, face));
            }
        }
    }

    @Test void realShapeRaysDistinguishTheHubTubeAndPlateOnEveryLaneAndFace() {
        for (ConduitKind kind : ConduitKind.values()) for (Direction face : Direction.values())
            for (int installed : new int[]{kind.mask(), 7}) {
                int bit = ConduitVisualState.bit(kind, face);
                var state = new ConduitVisualState(installed, bit, bit, 0, 0, 0);
                var parts = ConduitGeometry.parts(state).stream().filter(part -> part.kind() == kind).toList();
                var hub = parts.stream().filter(part -> part.role() == ConduitGeometry.Role.HUB).findFirst().orElseThrow();
                var tube = parts.stream().filter(part -> part.role() == ConduitGeometry.Role.TUBE).findFirst().orElseThrow();
                var plate = parts.stream().filter(part -> part.role() == ConduitGeometry.Role.ENDPOINT).findFirst().orElseThrow();
                assertTrue(ConduitGeometry.coveredTubeCap(tube, face, parts), "Only the covering plate paints the outer cap");
                assertFalse(ConduitGeometry.coveredTubeCap(plate, face, parts), "The visible plate is retained");
                for (Direction other : Direction.values()) if (other != face)
                    assertFalse(ConduitGeometry.coveredTubeCap(tube, other, parts), "Tube walls cannot be hidden by a terminal cap rule");
                AABB b = plate.bounds();
                AABB partial = face.getAxis() == Direction.Axis.X
                        ? new AABB(b.minX, b.minY, b.minZ, b.maxX, (b.minY + b.maxY) / 2, b.maxZ)
                        : new AABB(b.minX, b.minY, b.minZ, (b.minX + b.maxX) / 2, b.maxY, b.maxZ);
                assertFalse(ConduitGeometry.coveredTubeCap(tube, face, java.util.List.of(tube,
                        new ConduitGeometry.Part(kind, ConduitGeometry.Role.ENDPOINT, face, partial))),
                        "Partial coverage must not erase the rest of the tube cap");

                assertRayHit(state, surfaceCenter(hub.bounds(), face.getOpposite()), face.getOpposite(),
                        kind, ConduitGeometry.Role.HUB, null);
                // The tube and plate share their outer plane; the visible plate must win.
                assertRayHit(state, ConduitGeometry.faceCenter(state, kind, face), face,
                        kind, ConduitGeometry.Role.ENDPOINT, face);
                Direction normal = face.getAxis() == Direction.Axis.Y ? Direction.EAST : Direction.UP;
                double middle = (boundary(hub.bounds(), face) + boundary(plate.bounds(), face.getOpposite())) / 2;
                Vec3 tubeSide = withAxis(surfaceCenter(tube.bounds(), normal), face.getAxis(), middle);
                assertRayHit(state, tubeSide, normal, kind, ConduitGeometry.Role.TUBE, face);
                for (var part : parts) assertEquals(part.role() == ConduitGeometry.Role.HUB ? null : face, part.side());
            }
    }

    @Test void disabledExternalStubsHaveNoPlateAndInternalBendsKeepTheirBranchDirection() {
        for (ConduitKind kind : ConduitKind.values()) for (Direction face : Direction.values()) {
            int bit = ConduitVisualState.bit(kind, face);
            var disabled = new ConduitVisualState(kind.mask(), bit, 0, 0, 0, 0);
            assertTrue(ConduitGeometry.parts(disabled).stream().allMatch(part ->
                    part.role() == ConduitGeometry.Role.HUB || part.role() == ConduitGeometry.Role.TUBE));
            for (var part : ConduitGeometry.parts(disabled)) for (Direction normal : Direction.values())
                assertFalse(ConduitGeometry.coveredTubeCap(part, normal, ConduitGeometry.parts(disabled)),
                        "A disabled external stub keeps its exposed end and every sidewall");
            assertRayHit(disabled, ConduitGeometry.faceCenter(disabled, kind, face), face,
                    kind, ConduitGeometry.Role.TUBE, face);

            var internal = new ConduitVisualState(kind.mask(), bit, 0, 0, 0, kind.mask() << (face.ordinal() * 3));
            assertTrue(ConduitGeometry.parts(internal).stream().anyMatch(part ->
                    ConduitGeometry.coveredTubeCap(part, face, ConduitGeometry.parts(internal))));
            assertRayHit(internal, ConduitGeometry.faceCenter(internal, kind, face), face,
                    kind, ConduitGeometry.Role.COLLAR, face);
            var transition = new ConduitVisualState(kind.mask(), bit, 0, 0, 0, 7 << (face.ordinal() * 3));
            var parts = ConduitGeometry.parts(transition);
            assertEquals(1, parts.stream().filter(part -> part.role() == ConduitGeometry.Role.HUB).count(),
                    "Only the center is a repair hub; elbow fittings belong to their branch");
            for (var part : parts) assertEquals(part.role() == ConduitGeometry.Role.HUB ? null : face, part.side());
            assertRayHit(transition, ConduitGeometry.faceCenter(transition, kind, face), face,
                    kind, ConduitGeometry.Role.COLLAR, face);
        }
    }

    @Test void hitToleranceRejectsAirInteriorWrongNormalsAndBuriedFaces() {
        var state = new ConduitVisualState(ConduitKind.ITEM.mask(), 0, 0, 0, 0, 0);
        AABB hub = ConduitGeometry.parts(state).getFirst().bounds();
        Vec3 east = surfaceCenter(hub, Direction.EAST);
        assertTrue(ConduitGeometry.hitPart(state, east.add(0.5E-6, 0, 0), Direction.EAST).isPresent());
        assertTrue(ConduitGeometry.hitPart(state, east.add(2E-6, 0, 0), Direction.EAST).isEmpty());
        assertTrue(ConduitGeometry.hitPart(state, east, Direction.NORTH).isEmpty());
        assertTrue(ConduitGeometry.hitPart(state, hub.getCenter(), Direction.EAST).isEmpty());
        for (Vec3 point : new Vec3[]{Vec3.ZERO, new Vec3(100, .5, .5),
                new Vec3(Double.NaN, .5, .5), new Vec3(.5, Double.POSITIVE_INFINITY, .5)}) {
            assertTrue(ConduitGeometry.hitPart(state, point, Direction.EAST).isEmpty());
            assertTrue(ConduitGeometry.hitKind(state, point, Direction.EAST).isEmpty());
        }
        int bit = ConduitVisualState.bit(ConduitKind.ITEM, Direction.EAST);
        var connected = new ConduitVisualState(ConduitKind.ITEM.mask(), bit, bit, 0, 0, 0);
        assertTrue(ConduitGeometry.hitPart(connected, east, Direction.EAST).isEmpty(),
                "A hub boundary buried in its outgoing tube is not an exposed surface");
    }

    private static void assertRayHit(ConduitVisualState state, Vec3 surface, Direction normal,
                                     ConduitKind kind, ConduitGeometry.Role role, Direction branch) {
        Vec3 step = new Vec3(normal.getStepX(), normal.getStepY(), normal.getStepZ()).scale(.025);
        var hit = ConduitGeometry.shape(state).clip(surface.add(step), surface.subtract(step), BlockPos.ZERO);
        assertNotNull(hit, "The native shape ray must hit the intended visible surface");
        assertEquals(normal, hit.getDirection());
        assertTrue(hit.getLocation().distanceTo(surface) < 1E-7);
        var part = ConduitGeometry.hitPart(state, hit.getLocation(), hit.getDirection()).orElseThrow();
        assertEquals(kind, part.kind());
        assertEquals(role, part.role());
        assertEquals(branch, part.side());
        assertEquals(kind, ConduitGeometry.hitKind(state, hit.getLocation(), hit.getDirection()).orElseThrow());
    }

    private static double boundary(AABB box, Direction face) {
        return face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? face.getAxis().choose(box.maxX, box.maxY, box.maxZ)
                : face.getAxis().choose(box.minX, box.minY, box.minZ);
    }
    private static Vec3 surfaceCenter(AABB box, Direction face) {
        return withAxis(box.getCenter(), face.getAxis(), boundary(box, face));
    }
    private static Vec3 withAxis(Vec3 point, Direction.Axis axis, double value) {
        return switch (axis) {
            case X -> new Vec3(value, point.y, point.z);
            case Y -> new Vec3(point.x, value, point.z);
            case Z -> new Vec3(point.x, point.y, value);
        };
    }
}
