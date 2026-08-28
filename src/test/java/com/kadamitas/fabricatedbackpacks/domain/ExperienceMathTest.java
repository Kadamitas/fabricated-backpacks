package com.kadamitas.fabricatedbackpacks.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigInteger;
import static org.junit.jupiter.api.Assertions.*;

class ExperienceMathTest {
    @ParameterizedTest
    @CsvSource({
            "0,0,7", "1,7,9", "15,315,37", "16,352,42", "17,394,47",
            "30,1395,112", "31,1507,121", "32,1628,130", "100,30970,742"
    })
    void thresholdsFollowVanillaLevelBands(int level, long total, long next) {
        assertEquals(total, ExperienceMath.pointsAtLevel(level));
        assertEquals(next, ExperienceMath.pointsToNextLevel(level));
        assertEquals(level, ExperienceMath.levelAtPoints(total));
        assertEquals(level, ExperienceMath.levelAtPoints(total + next - 1));
        assertEquals(level + 1, ExperienceMath.levelAtPoints(total + next));
        if (level > 0) assertEquals(level - 1, ExperienceMath.levelAtPoints(total - 1));
    }

    @Test
    void totalsMatchAccumulatedLevelCosts() {
        long accumulated = 0;
        for (int level = 0; level < 10_001; level++) {
            assertEquals(accumulated, ExperienceMath.pointsAtLevel(level), "Level " + level);
            accumulated += ExperienceMath.pointsToNextLevel(level);
        }
    }

    @Test
    void fractionalProgressAndHugeTotalsStayInsideTheirLevel() {
        assertEquals(373, ExperienceMath.pointsAtProgress(16, 0.5));
        ExperienceMath.LevelProgress partial = ExperienceMath.splitPoints(373);
        assertEquals(16, partial.level());
        assertEquals(21, partial.pointsIntoLevel());
        assertEquals(0.5, partial.progress());
        assertEquals(Long.MAX_VALUE, ExperienceMath.pointsAtLevel(Integer.MAX_VALUE));
        for (long points : new long[]{Integer.MAX_VALUE, Long.MAX_VALUE / 2, Long.MAX_VALUE}) {
            ExperienceMath.LevelProgress split = ExperienceMath.splitPoints(points);
            assertTrue(split.level() < Integer.MAX_VALUE);
            assertEquals(points, ExperienceMath.pointsAtLevel(split.level()) + split.pointsIntoLevel());
            assertTrue(split.pointsIntoLevel() < ExperienceMath.pointsToNextLevel(split.level()));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 19, 20, 21, 159, 160, 161, Long.MAX_VALUE})
    void fluidConversionRetainsEveryFractionalPoint(long millibuckets) {
        long whole = ExperienceMath.wholePointsFromMillibuckets(millibuckets);
        int remainder = ExperienceMath.remainderMillibuckets(millibuckets);
        assertTrue(remainder >= 0 && remainder < 20);
        assertEquals(millibuckets, ExperienceMath.millibucketsForPoints(whole) + remainder);
    }

    @ParameterizedTest
    @CsvSource({
            "500,15,100,50,4", "0,0,1000,100,0", "5,0,1000,100,5",
            "100,100,100,100,0", "100,1,20,100,0", "100,19,120,100,5",
            "100,0,1000,0,0", "100,0,1000,3,3"
    })
    void storingAndTakingConserveResources(long player, long stored, long capacity, long request, long moved) {
        ExperienceMath.Exchange deposited = ExperienceMath.store(player, stored, capacity, request);
        assertEquals(moved, deposited.transferredPoints());
        assertEquals(total(player, stored), total(deposited.playerPoints(), deposited.storedMillibuckets()));
        assertTrue(deposited.storedMillibuckets() <= capacity);
        ExperienceMath.Exchange withdrawn = ExperienceMath.take(deposited.playerPoints(),
                deposited.storedMillibuckets(), capacity, moved);
        assertEquals(player, withdrawn.playerPoints());
        assertEquals(stored, withdrawn.storedMillibuckets());
        assertEquals(-moved, withdrawn.transferredPoints());
    }

    @ParameterizedTest
    @EnumSource(ExperienceMath.Direction.class)
    void automaticDirectionRespectsTargetProgress(ExperienceMath.Direction direction) {
        long target = ExperienceMath.pointsAtLevel(10);
        ExperienceMath.Exchange below = ExperienceMath.automate(direction, target - 5, 10, 1_000, 10_000, 3);
        ExperienceMath.Exchange exact = ExperienceMath.automate(direction, target, 10, 1_000, 10_000, 3);
        ExperienceMath.Exchange above = ExperienceMath.automate(direction, target + 5, 10, 1_000, 10_000, 3);
        boolean input = direction == ExperienceMath.Direction.INPUT || direction == ExperienceMath.Direction.KEEP;
        boolean output = direction == ExperienceMath.Direction.OUTPUT || direction == ExperienceMath.Direction.KEEP;
        assertEquals(output ? -3 : 0, below.transferredPoints());
        assertEquals(0, exact.transferredPoints());
        assertEquals(input ? 3 : 0, above.transferredPoints());
        assertEquals(total(target - 5, 1_000), total(below.playerPoints(), below.storedMillibuckets()));
        assertEquals(total(target + 5, 1_000), total(above.playerPoints(), above.storedMillibuckets()));
    }

    @Test
    void maximumQuantitiesCannotOverflowOrDeleteRemainders() {
        long convertible = Long.MAX_VALUE / 20;
        assertEquals(convertible, ExperienceMath.wholePointsFromMillibuckets(
                ExperienceMath.millibucketsForPoints(convertible)));
        assertThrows(ArithmeticException.class, () -> ExperienceMath.millibucketsForPoints(convertible + 1));
        ExperienceMath.Exchange fullPlayer = ExperienceMath.take(Long.MAX_VALUE - 1, 100, 100, 10);
        assertEquals(Long.MAX_VALUE, fullPlayer.playerPoints());
        assertEquals(80, fullPlayer.storedMillibuckets());
        assertEquals(-1, fullPlayer.transferredPoints());
        ExperienceMath.Exchange fullTank = ExperienceMath.store(Long.MAX_VALUE, 19, Long.MAX_VALUE, Long.MAX_VALUE);
        assertTrue(Long.MAX_VALUE - fullTank.storedMillibuckets() < 20);
        assertEquals(total(Long.MAX_VALUE, 19), total(fullTank.playerPoints(), fullTank.storedMillibuckets()));
        ExperienceMath.Exchange remainder = ExperienceMath.take(0, 19, 19, 100);
        assertEquals(0, remainder.playerPoints());
        assertEquals(19, remainder.storedMillibuckets());
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1, Double.NaN, Double.POSITIVE_INFINITY})
    void invalidFractionalProgressFails(double progress) {
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.pointsAtProgress(10, progress));
    }

    @Test
    void malformedLevelsAndResourceSnapshotsFail() {
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.pointsAtLevel(-1));
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.levelAtPoints(-1));
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.millibucketsForPoints(-1));
        assertThrows(IllegalArgumentException.class, () -> new ExperienceMath.LevelProgress(0, 7));
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.store(-1, 0, 100, 1));
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.store(1, 101, 100, 1));
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.take(1, 0, 100, -1));
        assertThrows(IllegalArgumentException.class, () -> ExperienceMath.automate(
                ExperienceMath.Direction.OFF, 0, -1, 0, 100, 1));
    }

    private static BigInteger total(long player, long fluid) {
        return BigInteger.valueOf(player).multiply(BigInteger.valueOf(20)).add(BigInteger.valueOf(fluid));
    }
}
