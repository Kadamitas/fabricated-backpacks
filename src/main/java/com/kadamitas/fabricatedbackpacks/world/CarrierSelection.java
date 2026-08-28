package com.kadamitas.fabricatedbackpacks.world;

import com.kadamitas.fabricatedbackpacks.config.ServerConfig;
import com.kadamitas.fabricatedbackpacks.domain.BackpackTier;

import java.util.Optional;
import java.util.function.IntUnaryOperator;

/** Exact integer weights avoid rounding off the rarest tier. */
public final class CarrierSelection {
    private CarrierSelection() { }
    public static int totalWeight(ServerConfig.Carriers rules, double difficulty) {
        int total = 0;
        for (int tier = rules.minimumTier(difficulty); tier < 6; tier++) total = Math.addExact(total, rules.tierWeights().get(tier));
        return total;
    }
    public static Optional<BackpackTier> choose(ServerConfig.Carriers rules, double difficulty, IntUnaryOperator randomBounded) {
        int total = totalWeight(rules, difficulty);
        if (total == 0) return Optional.empty();
        int ticket = randomBounded.applyAsInt(total);
        if (ticket < 0 || ticket >= total) throw new IllegalArgumentException("Random ticket is outside the weighted draw");
        for (int tier = rules.minimumTier(difficulty); tier < 6; tier++) {
            ticket -= rules.tierWeights().get(tier);
            if (ticket < 0) return Optional.of(BackpackTier.values()[tier]);
        }
        throw new IllegalStateException("Validated tier weights did not consume a ticket");
    }
}
