package com.kadamitas.fabricatedbackpacks.domain;

/** Geometry shared by the menu and its client view; it never changes inventory ownership. */
public record BackpackLayout(int columns, int visibleRows, int upgradeSlots, int panelColumns) {
    public static final int MIN_ROWS = 3;
    public static final int DEFAULT_ROWS = 6;
    public static final int MAX_ROWS = 12;
    public static final int MAX_VISIBLE_UPGRADES = 10;
    public static final int VIEWPORT_MARGIN = 8;
    private static final int MAX_RETAINED_UPGRADES = 256;

    public BackpackLayout {
        if (columns != 9 && columns != 12) throw new IllegalArgumentException("Storage columns must be 9 or 12");
        if (visibleRows < 1 || visibleRows > MAX_ROWS) throw new IllegalArgumentException("Visible rows must be 1..12");
        validateUpgradeSlots(upgradeSlots);
        if (panelColumns < 1 || panelColumns > 6) throw new IllegalArgumentException("Panel columns must be 1..6");
    }

    public int storagePanelX() { return 21; }
    public int storageWidth() { return columns * 18 + 14; }
    public int storageX() { return storagePanelX() + 8; }
    public int storageY() { return 18; }
    public int inventoryX() { return storagePanelX() + (storageWidth() - 176) / 2 + 8; }
    public int inventoryTitleY() { return storageY() + visibleRows * 18; }
    public int inventoryY() { return inventoryTitleY() + 14; }
    public int tabX() { return storagePanelX() + storageWidth(); }
    public int panelX() { return tabX(); }
    public int panelWidth() { return Math.max(84, panelColumns * 18 + 12); }
    public int imageWidth() { return panelX() + panelWidth(); }
    public int imageHeight() { return Math.max(inventoryY() + 82, upgradeRailHeight()); }
    public int upgradeSlotX() { return 6; }
    public int upgradeSlotY(int index) {
        if (index < 0 || index >= upgradeSlots) throw new IndexOutOfBoundsException("Upgrade slot outside saved inventory");
        return 6 + index * 16;
    }
    public int visibleUpgradeSlots() { return Math.min(upgradeSlots, MAX_VISIBLE_UPGRADES); }
    public int upgradeRailHeight() { return 12 + visibleUpgradeSlots() * 16; }

    /**
     * Returns a legal requested row count. A one- or two-row bag still requests three,
     * while its actual view uses min(totalRows, requested). If even that view cannot
     * fit, three remains the fallback; the caller must handle the undersized viewport.
     */
    public static int rowsForViewport(int totalRows, int viewportHeight, int upgrades) {
        if (totalRows < 1) throw new IllegalArgumentException("Total rows must be positive");
        if (viewportHeight < 0) throw new IllegalArgumentException("Viewport height must be nonnegative");
        validateUpgradeSlots(upgrades);
        int upper = Math.max(MIN_ROWS, Math.min(MAX_ROWS, totalRows));
        for (int requested = upper; requested >= MIN_ROWS; requested--) {
            int actualRows = Math.min(totalRows, requested);
            int imageHeight = new BackpackLayout(9, actualRows, upgrades, 4).imageHeight();
            if ((long) imageHeight + 2 * VIEWPORT_MARGIN <= viewportHeight) return requested;
        }
        return MIN_ROWS;
    }

    private static void validateUpgradeSlots(int upgrades) {
        // Saved component extents can outlive smaller configured defaults.
        if (upgrades < 0 || upgrades > MAX_RETAINED_UPGRADES)
            throw new IllegalArgumentException("Upgrade slots must be 0..256");
    }
}
