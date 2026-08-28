package com.kadamitas.fabricatedbackpacks.automation.engine;

/** One indivisible boiler work tick. Blocked ticks consume neither water nor unfinished fuel. */
public final class SteamEngineCycle {
    private SteamEngineCycle() { }

    public record Limits(long waterCapacity, long energyCapacity, long waterPerTick, long energyPerTick) {
        public Limits {
            if (waterCapacity <= 0 || energyCapacity <= 0 || waterPerTick <= 0 || energyPerTick <= 0
                    || waterPerTick > waterCapacity || energyPerTick > energyCapacity)
                throw new IllegalArgumentException("Invalid steam engine capacities or work quantum");
        }
    }

    public record Result(SteamEngineState state, boolean generated, boolean consumeFuel) { }

    public static Result step(SteamEngineState state, Limits limits, int freshFuelTicks, boolean remainderFits) {
        if (!state.enabled() || state.waterDroplets() < limits.waterPerTick() || state.waterDroplets() > limits.waterCapacity()
                || state.energy() > limits.energyCapacity() - limits.energyPerTick())
            return new Result(state, false, false);
        boolean newFuel = state.burnRemaining() == 0;
        if (newFuel && (freshFuelTicks <= 0 || !remainderFits)) return new Result(state, false, false);
        int remaining = newFuel ? freshFuelTicks : state.burnRemaining();
        int duration = newFuel ? freshFuelTicks : state.burnDuration();
        return new Result(new SteamEngineState(state.waterDroplets() - limits.waterPerTick(),
                state.energy() + limits.energyPerTick(), remaining - 1, duration, true), true, newFuel);
    }
}
