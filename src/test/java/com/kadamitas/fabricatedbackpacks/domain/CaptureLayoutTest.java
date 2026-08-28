package com.kadamitas.fabricatedbackpacks.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CaptureLayoutTest {
    @ParameterizedTest
    @CsvSource({
            "0,20,false,10", "20,20,false,20", "40,20,false,20",
            "5,20,false,13", "5,20,true,25", "20,40,false,30",
            "40,40,true,80", "0.0001,0.0001,false,1"
    })
    void costIncludesMaximumHealthAndCurrentHealth(double current, double maximum, boolean hostile, int expected) {
        assertEquals(expected, CaptureLayout.captureCost(current, maximum, hostile));
    }

    @Test
    void hardCostCeilingsRejectInsteadOfReducingCost() {
        assertTrue(CaptureLayout.withinUpgradeLimit(18, false, false, 18));
        assertFalse(CaptureLayout.withinUpgradeLimit(19, false, false, 18));
        assertFalse(CaptureLayout.withinUpgradeLimit(1, true, false, 18));
        assertTrue(CaptureLayout.withinUpgradeLimit(72, true, true, 72));
        assertFalse(CaptureLayout.withinUpgradeLimit(73, true, true, 72));
        assertEquals(40, CaptureLayout.captureCost(20, 20, true));
        assertEquals(Integer.MAX_VALUE, CaptureLayout.captureCost(Double.MAX_VALUE, Double.MAX_VALUE, true));
    }

    @Test
    void allocationRequiresContiguousRectangularSpace() {
        CaptureLayout fragmented = new CaptureLayout(3, 9, Set.of(1, 3, 5, 7));
        assertEquals(5, fragmented.slots() - fragmented.occupied().size());
        assertTrue(fragmented.find(2, 2).isEmpty());
        assertTrue(fragmented.allocate(4, 1, 1).isEmpty());
        CaptureLayout open = new CaptureLayout(3, 6, Set.of(0, 3));
        assertEquals(new CaptureLayout.Rectangle(1, 0, 2, 2), open.find(2, 2).orElseThrow());
    }

    @Test
    void partialRowsDoNotInventSlotsOrWrapRectangles() {
        CaptureLayout partial = new CaptureLayout(9, 10, Set.of());
        assertEquals(2, partial.rows());
        assertTrue(partial.find(2, 2).isEmpty());
        assertEquals(new CaptureLayout.Rectangle(0, 0, 1, 2), partial.find(1, 2).orElseThrow());
        assertThrows(IllegalArgumentException.class,
                () -> partial.cells(new CaptureLayout.Rectangle(1, 1, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> partial.cells(new CaptureLayout.Rectangle(8, 0, 2, 1)));
    }

    @Test
    void occupyAndReleaseConserveEveryCell() {
        CaptureLayout original = new CaptureLayout(4, 16, Set.of(15));
        CaptureLayout.Rectangle rectangle = original.allocate(5, 1, 1).orElseThrow();
        assertTrue(rectangle.area() >= 5);
        assertTrue(rectangle.width() > 1 && rectangle.height() > 1);
        CaptureLayout filled = original.occupy(rectangle);
        assertEquals(original.occupied().size() + rectangle.area(), filled.occupied().size());
        assertEquals(Set.of(15), original.occupied());
        assertEquals(original, filled.release(rectangle));
        assertThrows(IllegalArgumentException.class, () -> filled.occupy(rectangle));
        assertThrows(IllegalArgumentException.class, () -> original.release(rectangle));
    }

    @Test
    void oversizedEntitiesAndFullInventoriesDoNotAllocate() {
        CaptureLayout tiny = new CaptureLayout(3, 3, Set.of());
        assertTrue(tiny.allocate(4, 1, 1).isEmpty());
        assertTrue(new CaptureLayout(3, 3, Set.of(0, 1, 2)).allocate(1, 1, 1).isEmpty());
        assertTrue(new CaptureLayout(9, 0, Set.of()).find(1, 1).isEmpty());
    }

    @Test
    void occupancySnapshotsAreImmutable() {
        Set<Integer> source = new HashSet<>(Set.of(0));
        CaptureLayout layout = new CaptureLayout(3, 9, source);
        source.clear();
        assertEquals(Set.of(0), layout.occupied());
        assertThrows(UnsupportedOperationException.class, () -> layout.occupied().clear());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
    void invalidPhysicalDimensionsFail(double invalid) {
        CaptureLayout layout = new CaptureLayout(9, 27, Set.of());
        assertThrows(IllegalArgumentException.class, () -> layout.allocate(1, invalid, 1));
        assertThrows(IllegalArgumentException.class, () -> CaptureLayout.captureCost(1, invalid, false));
    }

    @Test
    void malformedGridAndCaptureStateFail() {
        assertThrows(IllegalArgumentException.class, () -> new CaptureLayout(0, 27, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new CaptureLayout(9, 145, Set.of()));
        assertThrows(IndexOutOfBoundsException.class, () -> new CaptureLayout(9, 27, Set.of(27)));
        assertThrows(IllegalArgumentException.class, () -> new CaptureLayout.Rectangle(-1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CaptureLayout.Rectangle(0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> CaptureLayout.captureCost(-1, 20, false));
        assertThrows(IllegalArgumentException.class, () -> CaptureLayout.withinUpgradeLimit(1, false, true, 121));
    }
}
