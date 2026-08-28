package com.kadamitas.fabricatedbackpacks.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StackCapacityTest {
    @ParameterizedTest
    @CsvSource({
            "64,1,64", "64,1.5,96", "16,1.5,24", "1,1.5,1", "64,0.125,8",
            "64,0.0625,4", "64,0.03125,2", "1,0.0001,1", "64,4096,262144",
            "64,2147483647,2147483647"
    })
    void itemCapacityMultipliesFloorsAndClamps(int normalLimit, double multiplier, int expected) {
        assertEquals(expected, StackCapacity.itemLimit(normalLimit, multiplier));
    }

    @Test
    void exclusionsNeverCreateAnOverstackedContainer() {
        assertEquals(1, StackCapacity.itemLimit(1, Double.MAX_VALUE, false));
        assertEquals(16, StackCapacity.itemLimit(16, 4, false));
        assertEquals(2, StackCapacity.itemLimit(16, 0.125, false));
        assertEquals(Integer.MAX_VALUE, StackCapacity.itemLimit(Integer.MAX_VALUE, Double.MAX_VALUE));
    }

    @Test
    void multipliersComposeAndClampOnlyAfterAllFactors() {
        assertEquals(3, StackCapacity.multiplier(List.of(UpgradeKind.STACK_UPGRADE_STARTER_TIER,
                UpgradeKind.STACK_UPGRADE_TIER_1, UpgradeKind.MAGNET)));
        List<UpgradeKind> factors = new ArrayList<>(List.of(UpgradeKind.STACK_UPGRADE_OMEGA_TIER,
                UpgradeKind.STACK_UPGRADE_TIER_4, UpgradeKind.STACK_DOWNGRADE_TIER_3));
        double expected = Integer.MAX_VALUE / 2.0;
        for (int rotation = 0; rotation < factors.size(); rotation++) {
            assertEquals(expected, StackCapacity.multiplier(factors));
            Collections.reverse(factors);
            assertEquals(expected, StackCapacity.multiplier(factors));
            Collections.reverse(factors);
            Collections.rotate(factors, 1);
        }
        assertEquals(1, StackCapacity.multiplier(List.of()));
    }

    @ParameterizedTest
    @CsvSource({
            "3,1,12000,30000", "3,1.5,18000,45000", "6,0.5,12000,30000",
            "10,0.03125,1250,3125", "9,4096,147456000,368640000",
            "10,2147483647,2147483647,2147483647"
    })
    void capacitiesScaleByStorageRows(int rows, double multiplier, int tank, int battery) {
        assertEquals(tank, StackCapacity.tankCapacity(rows, multiplier));
        assertEquals(battery, StackCapacity.batteryCapacity(rows, multiplier));
    }

    @Test
    void resourceRatioInterpolatesWithoutOverflow() {
        assertEquals(12_000, StackCapacity.resourceCapacity(3, 4_000, 16, 0, Integer.MAX_VALUE));
        assertEquals(102_000, StackCapacity.resourceCapacity(3, 4_000, 16, 0.5, Integer.MAX_VALUE));
        assertEquals(6_750, StackCapacity.resourceCapacity(3, 4_000, 0.125, 0.5, Integer.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, StackCapacity.resourceCapacity(3, Long.MAX_VALUE, 1, 1, Long.MAX_VALUE));
        assertEquals(1, StackCapacity.resourceCapacity(1, 1, Double.MIN_VALUE, 1, Long.MAX_VALUE));
    }

    @Test
    void rateFloorIsSpecificToTankOperations() {
        assertEquals(1_000, StackCapacity.tankTransferLimit(3, 1.5));
        assertEquals(90, StackCapacity.batteryTransferLimit(3, 1.5));
        assertEquals(1_920, StackCapacity.tankTransferLimit(6, 16));
        assertEquals(1, StackCapacity.batteryTransferLimit(1, 0.03125));
    }

    @Test
    void weakeningReportsOffendingSlotsWithoutAlteringCounts() {
        List<StackCapacity.StoredCount> contents = List.of(
                new StackCapacity.StoredCount(0, 64, 64, true),
                new StackCapacity.StoredCount(1, 65, 64, true),
                new StackCapacity.StoredCount(3, 2, 1, false),
                new StackCapacity.StoredCount(4, 0, 16, true));
        assertEquals(List.of(1, 3), StackCapacity.overflowingSlots(contents, 1));
        assertEquals(65, contents.get(1).count());
        assertEquals(List.of(3), StackCapacity.overflowingSlots(contents, 2));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
    void invalidMultipliersAreRejectedEvenForEmptyInventories(double invalid) {
        assertThrows(IllegalArgumentException.class, () -> StackCapacity.itemLimit(64, invalid));
        assertThrows(IllegalArgumentException.class, () -> StackCapacity.overflowingSlots(List.of(), invalid));
    }

    @Test
    void invalidDimensionsRatiosAndCountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> StackCapacity.itemLimit(0, 1));
        assertThrows(IllegalArgumentException.class, () -> StackCapacity.resourceCapacity(0, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> StackCapacity.resourceCapacity(1, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> StackCapacity.resourceCapacity(1, 1, 1, -0.1, 1));
        assertThrows(IllegalArgumentException.class, () -> StackCapacity.resourceCapacity(1, 1, 1, 1.1, 1));
        assertThrows(IllegalArgumentException.class, () -> StackCapacity.resourceCapacity(1, 1, 1, Double.NaN, 1));
        assertThrows(IllegalArgumentException.class, () -> new StackCapacity.StoredCount(-1, 1, 64, true));
        assertThrows(IllegalArgumentException.class, () -> new StackCapacity.StoredCount(0, -1, 64, true));
    }
}
