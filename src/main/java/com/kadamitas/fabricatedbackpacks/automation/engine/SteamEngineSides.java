package com.kadamitas.fabricatedbackpacks.automation.engine;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.core.Direction;

import java.util.Objects;

/** Immutable two-bit capability flags for each resource and physical face, without stored quantities. */
public record SteamEngineSides(long bits) {
    private static final long MASK = (1L << 36) - 1;
    public static final SteamEngineSides DEFAULT = new SteamEngineSides(defaultBits());
    public static final Codec<SteamEngineSides> CODEC = Codec.LONG.comapFlatMap(value -> valid(value)
                    ? DataResult.success(new SteamEngineSides(value))
                    : DataResult.error(() -> "Invalid steam engine side flags or energy input permission"),
            SteamEngineSides::bits);

    public SteamEngineSides {
        if (!valid(bits)) throw new IllegalArgumentException("Invalid steam engine side flags or energy input permission");
    }

    public EngineSideMode mode(ConduitKind kind, Direction side) {
        return EngineSideMode.values()[(int) ((bits >>> shift(kind, side)) & 3)];
    }

    public SteamEngineSides with(ConduitKind kind, Direction side, EngineSideMode mode) {
        Objects.requireNonNull(mode);
        if (kind == ConduitKind.ENERGY && mode.allowsInput())
            throw new IllegalArgumentException("The steam engine cannot receive energy");
        int shift = shift(kind, side);
        long changed = (bits & ~(3L << shift)) | ((long) mode.ordinal() << shift);
        return changed == bits ? this : new SteamEngineSides(changed);
    }

    /** An unsided query combines the available directions; it never bypasses six disabled faces. */
    public boolean allowsInput(ConduitKind kind, Direction side) { return allows(kind, side, true); }
    public boolean allowsOutput(ConduitKind kind, Direction side) { return allows(kind, side, false); }
    private boolean allows(ConduitKind kind, Direction side, boolean input) {
        if (side != null) return input ? mode(kind, side).allowsInput() : mode(kind, side).allowsOutput();
        for (Direction face : Direction.values())
            if (input ? mode(kind, face).allowsInput() : mode(kind, face).allowsOutput()) return true;
        return false;
    }
    private static int shift(ConduitKind kind, Direction side) {
        return (Objects.requireNonNull(kind).ordinal() * 6 + Objects.requireNonNull(side).ordinal()) * 2;
    }
    private static boolean valid(long bits) {
        if ((bits & ~MASK) != 0) return false;
        for (Direction side : Direction.values())
            if (((bits >>> shift(ConduitKind.ENERGY, side)) & 1) != 0) return false;
        return true;
    }
    private static long defaultBits() {
        long result = 0;
        for (ConduitKind kind : ConduitKind.values()) for (Direction face : Direction.values())
            result |= (long) (kind == ConduitKind.ENERGY ? EngineSideMode.OUTPUT : EngineSideMode.BOTH).ordinal() << shift(kind, face);
        return result;
    }
}
