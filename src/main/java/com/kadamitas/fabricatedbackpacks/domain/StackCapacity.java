package com.kadamitas.fabricatedbackpacks.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Capacity calculations never change stored quantities. Callers must reject unsafe reductions. */
public final class StackCapacity {
    private StackCapacity() {}

    public static double multiplier(Collection<UpgradeKind> upgrades) {
        Objects.requireNonNull(upgrades, "upgrades");
        BigDecimal result = BigDecimal.ONE;
        for (UpgradeKind upgrade : upgrades) {
            result = result.multiply(BigDecimal.valueOf(Objects.requireNonNull(upgrade).stackMultiplier()),
                    MathContext.DECIMAL128);
        }
        // Clamp only after composing: a later downgrade may undo an earlier large upgrade.
        double value = result.doubleValue();
        return Math.max(Double.MIN_VALUE, Math.min(Double.MAX_VALUE, value));
    }

    public static int itemLimit(int normalLimit, double multiplier) {
        return itemLimit(normalLimit, multiplier, true);
    }

    /** An excluded item may be reduced by a downgrade, but is never overstacked. */
    public static int itemLimit(int normalLimit, double multiplier, boolean allowOverstacking) {
        if (normalLimit < 1) throw new IllegalArgumentException("Normal stack limit must be positive");
        requirePositiveFinite(multiplier, "multiplier");
        long hardLimit = allowOverstacking ? Integer.MAX_VALUE : normalLimit;
        return (int) floorClamped(BigDecimal.valueOf(normalLimit).multiply(BigDecimal.valueOf(multiplier)), hardLimit);
    }

    /** Ratio is the fraction (0..1) of the stack multiplier applied to fluid/energy storage. */
    public static long resourceCapacity(int rows, long perRow, double multiplier, double ratio, long hardLimit) {
        if (rows < 1 || perRow < 1 || hardLimit < 1) {
            throw new IllegalArgumentException("Rows, per-row capacity, and hard limit must be positive");
        }
        requirePositiveFinite(multiplier, "multiplier");
        if (!Double.isFinite(ratio) || ratio < 0 || ratio > 1) {
            throw new IllegalArgumentException("Resource ratio must be between zero and one");
        }
        BigDecimal adjusted = BigDecimal.ONE.add(BigDecimal.valueOf(ratio)
                .multiply(BigDecimal.valueOf(multiplier).subtract(BigDecimal.ONE)));
        return floorClamped(adjusted.multiply(BigDecimal.valueOf(rows)).multiply(BigDecimal.valueOf(perRow)), hardLimit);
    }

    public static int tankCapacity(int rows, double multiplier) {
        return (int) resourceCapacity(rows, 4_000, multiplier, 1, Integer.MAX_VALUE);
    }

    public static int batteryCapacity(int rows, double multiplier) {
        return (int) resourceCapacity(rows, 10_000, multiplier, 1, Integer.MAX_VALUE);
    }

    public static int tankTransferLimit(int rows, double multiplier) {
        return Math.max(1_000, (int) resourceCapacity(rows, 20, multiplier, 1, Integer.MAX_VALUE));
    }

    public static int batteryTransferLimit(int rows, double multiplier) {
        return (int) resourceCapacity(rows, 20, multiplier, 1, Integer.MAX_VALUE);
    }

    public static List<Integer> overflowingSlots(List<StoredCount> contents, double proposedMultiplier) {
        Objects.requireNonNull(contents, "contents");
        requirePositiveFinite(proposedMultiplier, "proposedMultiplier");
        return contents.stream()
                .filter(stack -> stack.count() > itemLimit(stack.normalLimit(), proposedMultiplier, stack.allowOverstacking()))
                .map(StoredCount::slot).toList();
    }

    public record StoredCount(int slot, int count, int normalLimit, boolean allowOverstacking) {
        public StoredCount {
            if (slot < 0 || count < 0 || normalLimit < 1) {
                throw new IllegalArgumentException("Invalid stored stack count");
            }
        }
    }

    private static long floorClamped(BigDecimal value, long hardLimit) {
        if (value.compareTo(BigDecimal.ONE) < 0) return 1;
        if (value.compareTo(BigDecimal.valueOf(hardLimit)) >= 0) return hardLimit;
        return value.setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException(name + " must be finite and positive");
    }
}
