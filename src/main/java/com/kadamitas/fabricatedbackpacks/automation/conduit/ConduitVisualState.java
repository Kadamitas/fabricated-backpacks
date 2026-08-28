package com.kadamitas.fabricatedbackpacks.automation.conduit;

import net.minecraft.core.Direction;

/** Immutable public geometry data: no inventories, fluid variants, quantities, or network identities. */
public record ConduitVisualState(int installedMask, int connectionBits, int endpointBits,
                                 int extractBits, int insertBits, int neighborBits) {
    public static final ConduitVisualState EMPTY = new ConduitVisualState(0, 0, 0, 0, 0, 0);
    private static final int SIDE_BITS = (1 << 18) - 1;

    public ConduitVisualState {
        installedMask &= 7;
        int available = 0;
        for (Direction side : Direction.values()) available |= installedMask << (side.ordinal() * 3);
        connectionBits &= available;
        endpointBits &= connectionBits;
        extractBits &= endpointBits;
        insertBits &= endpointBits;
        neighborBits &= SIDE_BITS;
    }

    public static int bit(ConduitKind kind, Direction side) { return 1 << (side.ordinal() * 3 + kind.ordinal()); }
    public boolean has(ConduitKind kind) { return (installedMask & (1 << kind.ordinal())) != 0; }
    public boolean connected(ConduitKind kind, Direction side) { return (connectionBits & bit(kind, side)) != 0; }
    public boolean endpoint(ConduitKind kind, Direction side) { return (endpointBits & bit(kind, side)) != 0; }
    public boolean extracting(ConduitKind kind, Direction side) { return (extractBits & bit(kind, side)) != 0; }
    public boolean inserting(ConduitKind kind, Direction side) { return (insertBits & bit(kind, side)) != 0; }
    public int neighborMask(Direction side) { return (neighborBits >>> (side.ordinal() * 3)) & 7; }
    public int faceLayoutMask(Direction side) { return installedMask | neighborMask(side); }
    public int connectionMask(ConduitKind kind) {
        int mask = 0;
        for (Direction side : Direction.values()) if (connected(kind, side)) mask |= 1 << side.ordinal();
        return mask;
    }
}
