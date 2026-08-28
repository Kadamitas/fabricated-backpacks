package com.kadamitas.fabricatedbackpacks.resource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class FluidAmountTest {
    @ParameterizedTest
    @ValueSource(longs = {0, 1, 80, 81, 82, 1_620, 27_000, 81_000, Long.MAX_VALUE})
    void everyBoundaryRoundTripsExactly(long droplets) {
        FluidAmount split = FluidAmount.fromDroplets(droplets);
        assertEquals(droplets, split.droplets());
        assertTrue(split.remainderDroplets() >= 0 && split.remainderDroplets() < 81);
    }

    @Test
    void threeFabricBottlesMakeExactlyOneBucketWithoutRoundingAwayFluid() {
        FluidAmount bottle = FluidAmount.fromDroplets(27_000);
        assertEquals(new FluidAmount(333, 27), bottle);
        assertEquals(FluidAmount.dropletsForMb(1_000), Math.multiplyExact(3, bottle.droplets()));
        assertEquals(FluidAmount.dropletsForMb(20), FluidAmount.DROPLETS_PER_XP);
    }

    @Test
    void arbitrarilySplitTransfersConserveEveryDroplet() {
        Random random = new Random(0x51A77E);
        long original = 9_000_000_000_037L;
        FluidAmount source = FluidAmount.fromDroplets(original);
        FluidAmount destination = FluidAmount.fromDroplets(0);
        for (int operation = 0; operation < 2_000; operation++) {
            long moved = Math.min(source.droplets(), random.nextLong(81_000));
            source = FluidAmount.fromDroplets(source.droplets() - moved);
            destination = FluidAmount.fromDroplets(destination.droplets() + moved);
            assertEquals(original, source.droplets() + destination.droplets());
        }
    }

    @Test
    void malformedQuantitiesAndOverflowAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> FluidAmount.fromDroplets(-1));
        assertThrows(IllegalArgumentException.class, () -> new FluidAmount(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new FluidAmount(0, -1));
        assertThrows(IllegalArgumentException.class, () -> new FluidAmount(0, 81));
        assertThrows(IllegalArgumentException.class, () -> new FluidAmount(Long.MAX_VALUE / 81 + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new FluidAmount(Long.MAX_VALUE / 81, (int) (Long.MAX_VALUE % 81) + 1));
    }
}
