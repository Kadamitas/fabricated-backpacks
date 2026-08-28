package com.kadamitas.fabricatedbackpacks.resource;

/** Lossless bridge between displayed millibuckets and Fabric's 81 droplets per millibucket. */
public record FluidAmount(long millibuckets, int remainderDroplets) {
    public static final int DROPLETS_PER_MB = 81;
    public static final long DROPLETS_PER_XP = 20L * DROPLETS_PER_MB;

    public FluidAmount {
        if (millibuckets < 0 || millibuckets > Long.MAX_VALUE / DROPLETS_PER_MB
                || remainderDroplets < 0 || remainderDroplets >= DROPLETS_PER_MB
                || millibuckets * DROPLETS_PER_MB > Long.MAX_VALUE - remainderDroplets) {
            throw new IllegalArgumentException("Invalid fluid amount");
        }
    }

    public long droplets() { return millibuckets * DROPLETS_PER_MB + remainderDroplets; }

    public static FluidAmount fromDroplets(long droplets) {
        if (droplets < 0) throw new IllegalArgumentException("Fluid amount cannot be negative");
        return new FluidAmount(droplets / DROPLETS_PER_MB, (int) (droplets % DROPLETS_PER_MB));
    }

    public static long dropletsForMb(long millibuckets) {
        return new FluidAmount(millibuckets, 0).droplets();
    }
}
