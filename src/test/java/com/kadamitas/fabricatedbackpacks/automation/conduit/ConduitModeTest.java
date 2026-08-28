package com.kadamitas.fabricatedbackpacks.automation.conduit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class ConduitModeTest {
    @ParameterizedTest
    @CsvSource({"EXTRACT,true,false,true", "INSERT,false,true,true", "BOTH,true,true,true", "DISABLED,false,false,false"})
    void modesHaveUnambiguousNetworkRelativeDirections(ConduitMode mode, boolean extraction, boolean insertion, boolean connection) {
        assertEquals(extraction, mode.extracts());
        assertEquals(insertion, mode.inserts());
        assertEquals(connection, mode.connects());
    }

    @ParameterizedTest
    @EnumSource(ConduitRedstone.class)
    void redstoneOnlyPermitsTheSelectedSignalCondition(ConduitRedstone control) {
        assertEquals(control != ConduitRedstone.HIGH, control.permits(false));
        assertEquals(control != ConduitRedstone.LOW, control.permits(true));
        assertSame(control, control.next().next().next());
    }

    @Test
    void installDefaultsDoNotDrainItemOrFluidMachinesAndEveryModeIsReachable() {
        assertSame(ConduitMode.INSERT, ConduitMode.defaultFor(ConduitKind.ITEM));
        assertSame(ConduitMode.INSERT, ConduitMode.defaultFor(ConduitKind.FLUID));
        assertSame(ConduitMode.BOTH, ConduitMode.defaultFor(ConduitKind.ENERGY));
        var visited = java.util.EnumSet.noneOf(ConduitMode.class);
        ConduitMode mode = ConduitMode.EXTRACT;
        for (int i = 0; i < 4; i++) { visited.add(mode); mode = mode.next(); }
        assertEquals(java.util.EnumSet.allOf(ConduitMode.class), visited);
        assertSame(ConduitMode.EXTRACT, mode);
    }
}
