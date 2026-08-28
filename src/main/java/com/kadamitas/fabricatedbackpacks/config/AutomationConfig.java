package com.kadamitas.fabricatedbackpacks.config;

import java.util.Objects;

/** Server-owned limits for the independent conduit lanes and integrated steam generator. */
public record AutomationConfig(Conduits conduits, Engine engine) {
    public AutomationConfig {
        Objects.requireNonNull(conduits, "conduits");
        Objects.requireNonNull(engine, "engine");
    }

    public static AutomationConfig defaults() {
        return new AutomationConfig(Conduits.defaults(), Engine.defaults());
    }

    /** Bandwidth is shared by every face of a physical endpoint, not multiplied by connections. */
    public record Conduits(int itemsPerOperation, int itemIntervalTicks, int fluidMbPerTick,
                           long energyPerTick, int maximumNetworkNodes, int maximumEndpointVisitsPerTick) {
        public Conduits {
            range(itemsPerOperation, 1, 64, "itemsPerOperation");
            range(itemIntervalTicks, 1, 1_200, "itemIntervalTicks");
            range(fluidMbPerTick, 1, 1_000_000, "fluidMbPerTick");
            range(energyPerTick, 1, 1_000_000_000, "energyPerTick");
            range(maximumNetworkNodes, 1, 16_384, "maximumNetworkNodes");
            range(maximumEndpointVisitsPerTick, 1, 4_096, "maximumEndpointVisitsPerTick");
        }

        public static Conduits defaults() { return new Conduits(8, 10, 100, 256, 2_048, 128); }
    }

    /** Water is configured in millibuckets and stored as exact Fabric droplets by the engine. */
    public record Engine(int waterCapacityMb, long energyCapacity, int waterMbPerTick,
                         long energyPerTick, long energyOutputPerTick, int containerTransferMbPerTick) {
        public Engine {
            range(waterCapacityMb, 1, 1_000_000, "waterCapacityMb");
            range(energyCapacity, 1, 1_000_000_000_000L, "energyCapacity");
            range(waterMbPerTick, 1, waterCapacityMb, "waterMbPerTick");
            range(energyPerTick, 1, energyCapacity, "energyPerTick");
            range(energyOutputPerTick, 1, 1_000_000_000, "energyOutputPerTick");
            range(containerTransferMbPerTick, 1, 1_000_000, "containerTransferMbPerTick");
        }

        public static Engine defaults() { return new Engine(4_000, 32_000, 1, 40, 256, 1_000); }
    }

    private static void range(long value, long minimum, long maximum, String field) {
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(field + " must be in " + minimum + ".." + maximum);
    }
}
