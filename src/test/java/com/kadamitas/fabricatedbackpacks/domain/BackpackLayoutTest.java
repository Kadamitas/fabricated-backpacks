package com.kadamitas.fabricatedbackpacks.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class BackpackLayoutTest {
    @ParameterizedTest
    @CsvSource({
            "9,3,1,4,176,29,86,281,168",
            "12,6,7,4,230,56,140,335,222",
            "12,10,7,4,230,56,212,335,294",
            "12,12,10,6,230,56,248,371,330",
            "9,1,0,1,176,29,50,281,132"
    })
    void geometryMatchesTheStorageRailAndCenteredInventory(int columns, int rows, int upgrades, int panelColumns,
                                                          int storageWidth, int inventoryX, int inventoryY,
                                                          int imageWidth, int imageHeight) {
        var layout = new BackpackLayout(columns, rows, upgrades, panelColumns);
        assertAll(
                () -> assertEquals(21, layout.storagePanelX()),
                () -> assertEquals(29, layout.storageX()),
                () -> assertEquals(18, layout.storageY()),
                () -> assertEquals(storageWidth, layout.storageWidth()),
                () -> assertEquals(inventoryX, layout.inventoryX()),
                () -> assertEquals(inventoryY - 14, layout.inventoryTitleY()),
                () -> assertEquals(inventoryY, layout.inventoryY()),
                () -> assertEquals(imageWidth, layout.imageWidth()),
                () -> assertEquals(imageHeight, layout.imageHeight()));
    }

    @Test
    void physicalSlotsClearTheStorageAndSelectedPanelAttachesAtTheTab() {
        var layout = new BackpackLayout(12, 6, 7, 6);
        assertEquals(6, layout.upgradeSlotX());
        assertEquals(6, layout.upgradeSlotY(0));
        assertEquals(102, layout.upgradeSlotY(6));
        assertEquals(7, layout.storageX() - layout.upgradeSlotX() - 16);
        assertEquals(251, layout.tabX());
        assertEquals(layout.tabX(), layout.panelX());
        assertEquals(120, layout.panelWidth());
        assertEquals(layout.imageWidth(), layout.panelX() + layout.panelWidth());
        assertThrows(IndexOutOfBoundsException.class, () -> layout.upgradeSlotY(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> layout.upgradeSlotY(7));
    }

    @Test
    void retainedUpgradeExtentsDoNotStretchTheVisibleRailOffscreen() {
        var layout = new BackpackLayout(9, 3, 256, 4);
        assertEquals(256, layout.upgradeSlots());
        assertEquals(10, layout.visibleUpgradeSlots());
        assertEquals(172, layout.upgradeRailHeight());
        assertEquals(172, layout.imageHeight());
        var empty = new BackpackLayout(9, 1, 0, 4);
        assertEquals(0, empty.visibleUpgradeSlots());
        assertThrows(IndexOutOfBoundsException.class, () -> empty.upgradeSlotY(0));
    }

    @ParameterizedTest
    @CsvSource({
            "10,237,7,5", "10,238,7,6",
            "10,309,7,9", "10,310,7,10",
            "12,345,10,11", "12,346,10,12",
            "12,201,10,3", "12,202,10,4",
            "1,148,0,3", "2,166,0,3",
            "22,1000,256,12", "6,1000,0,6",
            "12,0,0,3", "12,2147483647,0,12"
    })
    void viewportUsesTheLargestFittingRequestWithEightPixelsOnEachSide(int totalRows, int height, int upgrades, int expected) {
        assertEquals(expected, BackpackLayout.rowsForViewport(totalRows, height, upgrades));
    }

    @Test
    void viewportSelectionIsMaximalAcrossSavedRowsAndRailSizes() {
        for (int totalRows = 1; totalRows <= 22; totalRows++) {
            for (int height = 0; height <= 400; height += 17) {
                for (int upgrades : new int[]{0, 1, 7, 10, 256}) {
                    int selected = BackpackLayout.rowsForViewport(totalRows, height, upgrades);
                    int upper = Math.max(3, Math.min(12, totalRows));
                    String scenario = "rows=" + totalRows + ", viewport=" + height + ", upgrades=" + upgrades;
                    assertTrue(selected >= 3 && selected <= upper, scenario);
                    var layout = new BackpackLayout(12, Math.min(totalRows, selected), upgrades, 4);
                    if (layout.imageHeight() + 16 > height) assertEquals(3, selected, scenario);
                    if (selected < upper) {
                        var next = new BackpackLayout(12, Math.min(totalRows, selected + 1), upgrades, 4);
                        assertTrue(next.imageHeight() + 16 > height, scenario);
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "8,6,7,4", "10,6,7,4", "12,0,7,4", "12,13,7,4",
            "12,6,-1,4", "12,6,257,4", "12,6,7,0", "12,6,7,7"
    })
    void malformedGeometryIsRejected(int columns, int rows, int upgrades, int panelColumns) {
        assertThrows(IllegalArgumentException.class, () -> new BackpackLayout(columns, rows, upgrades, panelColumns));
    }

    @ParameterizedTest
    @CsvSource({"0,480,0", "1,-1,0", "1,480,-1", "1,480,257"})
    void malformedViewportRequestsAreRejected(int rows, int height, int upgrades) {
        assertThrows(IllegalArgumentException.class, () -> BackpackLayout.rowsForViewport(rows, height, upgrades));
    }
}
