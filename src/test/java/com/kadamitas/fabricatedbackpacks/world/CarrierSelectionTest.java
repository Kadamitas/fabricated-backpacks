package com.kadamitas.fabricatedbackpacks.world;

import com.kadamitas.fabricatedbackpacks.config.ConfigFile;
import com.kadamitas.fabricatedbackpacks.config.ServerConfig;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class CarrierSelectionTest {
    @Test void eachWeightedTicketIsConsumedByExactlyItsTier() {
        var config = ServerConfig.defaults().carriers();
        int[] actual = new int[6];
        int total = CarrierSelection.totalWeight(config, 0);
        for (int ticket = 0; ticket < total; ticket++) {
            int draw = ticket;
            actual[CarrierSelection.choose(config, 0, ignored -> draw).orElseThrow().ordinal()]++;
        }
        assertArrayEquals(new int[]{625,250,125,25,5,1}, actual);
        assertEquals(total, Arrays.stream(actual).sum());
    }

    @ParameterizedTest @CsvSource({"0,0,1031", "1.999,0,1031", "2,1,406", "3.999,1,406", "4,2,156", "100,2,156"})
    void difficultyBoundariesRemoveLowerTiersWithoutRoundingRareOnes(double difficulty, int minimum, int total) {
        var rules = ServerConfig.defaults().carriers();
        assertEquals(total, CarrierSelection.totalWeight(rules, difficulty));
        assertEquals(BackpackTier.values()[minimum], CarrierSelection.choose(rules, difficulty, ignored -> 0).orElseThrow());
        assertEquals(BackpackTier.NETHERITE, CarrierSelection.choose(rules, difficulty, bound -> bound - 1).orElseThrow());
    }

    @Test void aDisabledEligiblePoolCannotFallBackToADisabledTier() {
        var rules = ConfigFile.decode("{\"carriers\":{\"tierWeights\":[1,0,0,0,0,0]}}").carriers();
        assertTrue(CarrierSelection.choose(rules, 4, ignored -> fail("Empty weighted pools must not draw randomness")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> CarrierSelection.choose(rules, 0, bound -> bound));
        assertThrows(IllegalArgumentException.class, () -> CarrierSelection.choose(rules, 0, bound -> -1));
        assertThrows(IllegalArgumentException.class, () -> CarrierSelection.totalWeight(rules, Double.NaN));
    }
}
