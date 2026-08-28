package com.kadamitas.fabricatedbackpacks.automation.engine;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** Persisted resources and unfinished fuel work; animation and transfer allowances are transient. */
public record SteamEngineState(long waterDroplets, long energy, int burnRemaining, int burnDuration,
                               boolean enabled) {
    public static final SteamEngineState EMPTY = new SteamEngineState(0, 0, 0, 0, true);
    private static final Codec<Long> NONNEGATIVE_LONG = Codec.LONG.validate(value -> value >= 0
            ? DataResult.success(value) : DataResult.error(() -> "Steam engine quantities cannot be negative"));
    private record Encoded(long water, long energy, int remaining, int duration, boolean enabled) { }
    private static final Codec<Encoded> FIELDS = RecordCodecBuilder.create(instance -> instance.group(
            NONNEGATIVE_LONG.optionalFieldOf("water", 0L).forGetter(Encoded::water),
            NONNEGATIVE_LONG.optionalFieldOf("energy", 0L).forGetter(Encoded::energy),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("burn_remaining", 0).forGetter(Encoded::remaining),
            Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("burn_duration", 0).forGetter(Encoded::duration),
            Codec.BOOL.optionalFieldOf("enabled", true).forGetter(Encoded::enabled)
    ).apply(instance, Encoded::new));
    public static final Codec<SteamEngineState> CODEC = FIELDS.comapFlatMap(encoded -> encoded.remaining() > encoded.duration()
            ? DataResult.error(() -> "Remaining steam engine fuel exceeds its original duration")
            : DataResult.success(new SteamEngineState(encoded.water(), encoded.energy(), encoded.remaining(), encoded.duration(), encoded.enabled())),
            state -> new Encoded(state.waterDroplets(), state.energy(), state.burnRemaining(), state.burnDuration(), state.enabled()));

    public SteamEngineState {
        if (waterDroplets < 0 || energy < 0 || burnRemaining < 0 || burnDuration < burnRemaining)
            throw new IllegalArgumentException("Invalid steam engine resource state");
    }

    public SteamEngineState resources(long water, long storedEnergy) {
        return new SteamEngineState(water, storedEnergy, burnRemaining, burnDuration, enabled);
    }

    public SteamEngineState enabled(boolean value) {
        return new SteamEngineState(waterDroplets, energy, burnRemaining, burnDuration, value);
    }
}
