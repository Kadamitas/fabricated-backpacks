package com.kadamitas.fabricatedbackpacks.domain;

import java.math.BigInteger;
import java.util.Objects;

/** Integer XP points are conserved; a tank may retain a remainder smaller than one point. */
public final class ExperienceMath {
    public static final int MILLIBUCKETS_PER_POINT = 20;
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    public enum Direction { INPUT, OUTPUT, KEEP, OFF }

    private ExperienceMath() {}

    /** Total points at the beginning of a vanilla level, saturated only at the long boundary. */
    public static long pointsAtLevel(int level) {
        return exactPointsAtLevel(level).min(LONG_MAX).longValueExact();
    }

    public static long pointsToNextLevel(int level) {
        requireLevel(level);
        if (level >= 30) return 9L * level - 158;
        if (level >= 15) return 5L * level - 38;
        return 2L * level + 7;
    }

    /** Greatest fully earned level. Comparing exact totals avoids saturated-level ambiguity. */
    public static int levelAtPoints(long points) {
        requireNonnegative(points, "points");
        BigInteger available = BigInteger.valueOf(points);
        int low = 0;
        int high = (int) Math.min(Integer.MAX_VALUE, points / 7 + 1);
        while (low < high) {
            int middle = (int) (low + ((long) high - low + 1) / 2);
            if (exactPointsAtLevel(middle).compareTo(available) <= 0) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    public static long pointsAtProgress(int level, double progress) {
        requireLevel(level);
        if (!Double.isFinite(progress) || progress < 0 || progress >= 1) {
            throw new IllegalArgumentException("Level progress must be in [0,1)");
        }
        long base = pointsAtLevel(level);
        long partial = (long) Math.floor(pointsToNextLevel(level) * progress);
        return partial > Long.MAX_VALUE - base ? Long.MAX_VALUE : base + partial;
    }

    public static LevelProgress splitPoints(long points) {
        int level = levelAtPoints(points);
        return new LevelProgress(level, points - pointsAtLevel(level));
    }

    public record LevelProgress(int level, long pointsIntoLevel) {
        public LevelProgress {
            requireLevel(level);
            if (pointsIntoLevel < 0 || pointsIntoLevel >= pointsToNextLevel(level)) {
                throw new IllegalArgumentException("Points must be inside the selected level");
            }
        }

        public double progress() { return (double) pointsIntoLevel / pointsToNextLevel(level); }
    }

    /** Exact conversion; unrepresentable quantities are rejected rather than silently lost. */
    public static long millibucketsForPoints(long points) {
        requireNonnegative(points, "points");
        return Math.multiplyExact(points, MILLIBUCKETS_PER_POINT);
    }

    public static long wholePointsFromMillibuckets(long millibuckets) {
        requireNonnegative(millibuckets, "millibuckets");
        return millibuckets / MILLIBUCKETS_PER_POINT;
    }

    public static int remainderMillibuckets(long millibuckets) {
        requireNonnegative(millibuckets, "millibuckets");
        return (int) (millibuckets % MILLIBUCKETS_PER_POINT);
    }

    /** Positive transferredPoints means player to tank, negative means tank to player. */
    public record Exchange(long playerPoints, long storedMillibuckets, long transferredPoints) {
        public Exchange {
            requireNonnegative(playerPoints, "playerPoints");
            requireNonnegative(storedMillibuckets, "storedMillibuckets");
            if (transferredPoints < -(Long.MAX_VALUE / MILLIBUCKETS_PER_POINT)
                    || transferredPoints > Long.MAX_VALUE / MILLIBUCKETS_PER_POINT) {
                throw new IllegalArgumentException("Transferred XP cannot be represented as fluid");
            }
        }
    }

    public static Exchange store(long playerPoints, long storedMillibuckets, long capacityMillibuckets,
                                 long requestedPoints) {
        validateResources(playerPoints, storedMillibuckets, capacityMillibuckets, requestedPoints);
        long moved = Math.min(requestedPoints, Math.min(playerPoints,
                (capacityMillibuckets - storedMillibuckets) / MILLIBUCKETS_PER_POINT));
        return new Exchange(playerPoints - moved, storedMillibuckets + millibucketsForPoints(moved), moved);
    }

    public static Exchange take(long playerPoints, long storedMillibuckets, long capacityMillibuckets,
                                long requestedPoints) {
        validateResources(playerPoints, storedMillibuckets, capacityMillibuckets, requestedPoints);
        long moved = Math.min(requestedPoints, Math.min(storedMillibuckets / MILLIBUCKETS_PER_POINT,
                Long.MAX_VALUE - playerPoints));
        return new Exchange(playerPoints + moved, storedMillibuckets - millibucketsForPoints(moved), -moved);
    }

    /** The target is the start of a level, so progress above an equal target is excess XP. */
    public static Exchange automate(Direction direction, long playerPoints, int targetLevel,
                                    long storedMillibuckets, long capacityMillibuckets, long maxPoints) {
        Objects.requireNonNull(direction, "direction");
        validateResources(playerPoints, storedMillibuckets, capacityMillibuckets, maxPoints);
        long target = pointsAtLevel(targetLevel);
        if ((direction == Direction.INPUT || direction == Direction.KEEP) && playerPoints > target) {
            return store(playerPoints, storedMillibuckets, capacityMillibuckets, Math.min(maxPoints, playerPoints - target));
        }
        if ((direction == Direction.OUTPUT || direction == Direction.KEEP) && playerPoints < target) {
            return take(playerPoints, storedMillibuckets, capacityMillibuckets, Math.min(maxPoints, target - playerPoints));
        }
        return new Exchange(playerPoints, storedMillibuckets, 0);
    }

    private static BigInteger exactPointsAtLevel(int level) {
        requireLevel(level);
        BigInteger n = BigInteger.valueOf(level);
        BigInteger square = n.multiply(n);
        if (level <= 16) return square.add(n.multiply(BigInteger.valueOf(6)));
        if (level <= 31) return square.multiply(BigInteger.valueOf(5))
                .subtract(n.multiply(BigInteger.valueOf(81))).add(BigInteger.valueOf(720)).divide(BigInteger.TWO);
        return square.multiply(BigInteger.valueOf(9)).subtract(n.multiply(BigInteger.valueOf(325)))
                .add(BigInteger.valueOf(4_440)).divide(BigInteger.TWO);
    }

    private static void validateResources(long points, long stored, long capacity, long requested) {
        requireNonnegative(points, "playerPoints");
        requireNonnegative(stored, "storedMillibuckets");
        requireNonnegative(capacity, "capacityMillibuckets");
        requireNonnegative(requested, "requestedPoints");
        if (stored > capacity) throw new IllegalArgumentException("Stored XP exceeds capacity");
    }

    private static void requireLevel(int level) {
        if (level < 0) throw new IllegalArgumentException("Level cannot be negative");
    }

    private static void requireNonnegative(long value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " cannot be negative");
    }
}
