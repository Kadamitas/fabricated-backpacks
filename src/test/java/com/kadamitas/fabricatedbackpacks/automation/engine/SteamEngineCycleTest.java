package com.kadamitas.fabricatedbackpacks.automation.engine;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SteamEngineCycleTest {
    private static final SteamEngineCycle.Limits LIMITS = new SteamEngineCycle.Limits(324_000, 32_000, 81, 40);

    @Test void oneProductiveTickStartsExactlyOneFuelAndSpendsOneWaterQuantum() {
        var result = SteamEngineCycle.step(new SteamEngineState(81, 0, 0, 0, true), LIMITS, 1_600, true);
        assertTrue(result.generated());
        assertTrue(result.consumeFuel());
        assertEquals(new SteamEngineState(0, 40, 1_599, 1_600, true), result.state());
    }

    @Test void blockedTicksPreserveEveryResourceAndUnfinishedFuel() {
        for (var state : new SteamEngineState[]{
                new SteamEngineState(80, 0, 73, 100, true),
                new SteamEngineState(81, 32_000, 73, 100, true),
                new SteamEngineState(81, 31_961, 73, 100, true),
                new SteamEngineState(324_001, 0, 73, 100, true),
                new SteamEngineState(81, 0, 73, 100, false),
                new SteamEngineState(81, Long.MAX_VALUE, 73, 100, true)}) {
            var result = SteamEngineCycle.step(state, LIMITS, 20_000, true);
            assertSame(state, result.state());
            assertFalse(result.generated());
            assertFalse(result.consumeFuel());
        }
    }

    @Test void remainderSpaceMattersOnlyWhenAnotherFuelItemWouldBeConsumed() {
        var before = new SteamEngineState(162, 0, 0, 0, true);
        assertSame(before, SteamEngineCycle.step(before, LIMITS, 20_000, false).state());
        assertSame(before, SteamEngineCycle.step(before, LIMITS, 0, true).state());
        var burning = new SteamEngineState(162, 0, 1, 20_000, true);
        var result = SteamEngineCycle.step(burning, LIMITS, 0, false);
        assertTrue(result.generated());
        assertFalse(result.consumeFuel());
        assertEquals(new SteamEngineState(81, 40, 0, 20_000, true), result.state());
    }

    @Test void exactFitAndLargeLongCountersNeverOverflow() {
        var limits = new SteamEngineCycle.Limits(Long.MAX_VALUE, Long.MAX_VALUE, 81, 40);
        var full = SteamEngineCycle.step(new SteamEngineState(Long.MAX_VALUE, Long.MAX_VALUE - 40, 2, 2, true), limits, 0, true);
        assertEquals(Long.MAX_VALUE, full.state().energy());
        assertEquals(Long.MAX_VALUE - 81, full.state().waterDroplets());
        assertSame(full.state(), SteamEngineCycle.step(full.state(), limits, 0, true).state());
    }

    @Test void seededSequenceConservesFuelWorkWaterAndEnergyAcrossPauses() {
        Random random = new Random(0xB011E2);
        var state = new SteamEngineState(200_000, 0, 0, 0, true);
        long initialWater = state.waterDroplets(), generation = 0, work = 0;
        for (int tick = 0; tick < 4_000; tick++) {
            state = state.enabled(random.nextInt(5) != 0);
            var result = SteamEngineCycle.step(state, LIMITS, 17, random.nextBoolean());
            if (result.consumeFuel()) work += 17;
            if (result.generated()) { generation++; work--; }
            state = result.state();
            assertEquals(initialWater, state.waterDroplets() + generation * 81);
            assertEquals(generation * 40, state.energy());
            assertEquals(work, state.burnRemaining());
            assertTrue(state.energy() <= LIMITS.energyCapacity());
        }
        assertEquals(800, generation);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 32_767, 32_768, 65_535, 65_536, 2_147_483_647, 2_147_483_648L, 1_000_000_000_000L, Long.MAX_VALUE})
    void nativeSignedShortWordsRoundTripEveryCounter(long amount) {
        assertEquals(amount, SteamEngineWords.join((short) SteamEngineWords.word(amount, 0),
                (short) SteamEngineWords.word(amount, 1), (short) SteamEngineWords.word(amount, 2),
                (short) SteamEngineWords.word(amount, 3)));
    }

    @Test void invalidStateAndWorkLimitsFailBeforeAnyMutation() {
        assertThrows(IllegalArgumentException.class, () -> new SteamEngineState(-1, 0, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new SteamEngineState(0, -1, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new SteamEngineState(0, 0, 2, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new SteamEngineCycle.Limits(10, 10, 11, 1));
        assertThrows(IllegalArgumentException.class, () -> new SteamEngineCycle.Limits(10, 10, 1, 11));
        assertThrows(IllegalArgumentException.class, () -> new SteamEngineCycle.Limits(10, 10, 0, 1));
    }

    @Test void savedStateCodecRejectsMalformedQuantitiesAndCrossFieldFuelWithoutThrowing() {
        for (String invalid : new String[]{"{\"water\":-1}", "{\"energy\":-1}",
                "{\"burn_remaining\":2,\"burn_duration\":1}", "{\"burn_duration\":-1}"})
            assertTrue(SteamEngineState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(invalid)).error().isPresent());
        var state = new SteamEngineState(27_027, 712_345_678_901L, 70_001, 80_003, false);
        var json = SteamEngineState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        assertEquals(state, SteamEngineState.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
    }

    @Test void sideDefaultsAndCyclesKeepTheGeneratorOutputOnly() {
        for (ConduitKind kind : ConduitKind.values()) for (Direction side : Direction.values()) {
            var mode = SteamEngineSides.DEFAULT.mode(kind, side);
            assertEquals(kind == ConduitKind.ENERGY ? EngineSideMode.OUTPUT : EngineSideMode.BOTH, mode);
            assertTrue(mode.allowsOutput());
            assertEquals(kind != ConduitKind.ENERGY, mode.allowsInput());
            int length = kind == ConduitKind.ENERGY ? 2 : 4;
            var cursor = mode;
            var visited = new java.util.HashSet<EngineSideMode>();
            for (int step = 0; step < length; step++) {
                assertTrue(visited.add(cursor));
                if (kind == ConduitKind.ENERGY) assertFalse(cursor.allowsInput());
                cursor = cursor.next(kind);
            }
            assertEquals(mode, cursor);
        }
    }

    @Test void immutableSideChangesAffectOnlyTheSelectedResourceAndPhysicalFace() {
        for (ConduitKind kind : ConduitKind.values()) for (Direction side : Direction.values()) {
            var changed = SteamEngineSides.DEFAULT.with(kind, side, EngineSideMode.DISABLED);
            for (ConduitKind otherKind : ConduitKind.values()) for (Direction otherSide : Direction.values())
                assertEquals(otherKind == kind && otherSide == side ? EngineSideMode.DISABLED
                                : SteamEngineSides.DEFAULT.mode(otherKind, otherSide), changed.mode(otherKind, otherSide));
            assertSame(changed, changed.with(kind, side, EngineSideMode.DISABLED));
            assertNotEquals(SteamEngineSides.DEFAULT, changed);
        }
    }

    @Test void unsidedPermissionsCombineEnabledFacesWithoutBypassingDisabledConfiguration() {
        var closed = new SteamEngineSides(0);
        for (ConduitKind kind : ConduitKind.values()) {
            assertFalse(closed.allowsInput(kind, null));
            assertFalse(closed.allowsOutput(kind, null));
        }
        var partial = closed.with(ConduitKind.ITEM, Direction.EAST, EngineSideMode.INPUT)
                .with(ConduitKind.FLUID, Direction.WEST, EngineSideMode.OUTPUT)
                .with(ConduitKind.ENERGY, Direction.UP, EngineSideMode.OUTPUT);
        assertTrue(partial.allowsInput(ConduitKind.ITEM, null));
        assertFalse(partial.allowsOutput(ConduitKind.ITEM, null));
        assertFalse(partial.allowsInput(ConduitKind.ITEM, Direction.WEST));
        assertFalse(partial.allowsInput(ConduitKind.FLUID, null));
        assertTrue(partial.allowsOutput(ConduitKind.FLUID, null));
        assertFalse(partial.allowsInput(ConduitKind.ENERGY, null));
        assertTrue(partial.allowsOutput(ConduitKind.ENERGY, null));
    }

    @Test void sideCodecPreservesHighFlagsAndRejectsInvalidInputCapabilities() {
        for (SteamEngineSides sides : new SteamEngineSides[]{new SteamEngineSides(0), SteamEngineSides.DEFAULT,
                SteamEngineSides.DEFAULT.with(ConduitKind.ITEM, Direction.NORTH, EngineSideMode.OUTPUT)}) {
            var encoded = SteamEngineSides.CODEC.encodeStart(JsonOps.INSTANCE, sides).getOrThrow();
            assertEquals(sides, SteamEngineSides.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        }
        assertTrue(SteamEngineSides.DEFAULT.bits() > Integer.MAX_VALUE);
        for (long invalid : new long[]{-1, 1L << 36, 1L << 24}) {
            assertThrows(IllegalArgumentException.class, () -> new SteamEngineSides(invalid));
            assertTrue(SteamEngineSides.CODEC.parse(JsonOps.INSTANCE, new com.google.gson.JsonPrimitive(invalid)).error().isPresent());
        }
        for (EngineSideMode mode : new EngineSideMode[]{EngineSideMode.INPUT, EngineSideMode.BOTH})
            assertThrows(IllegalArgumentException.class, () -> SteamEngineSides.DEFAULT.with(ConduitKind.ENERGY, Direction.DOWN, mode));
    }
}
