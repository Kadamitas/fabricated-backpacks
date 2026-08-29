package com.kadamitas.fabricatedbackpacks.automation.engine;

import com.kadamitas.fabricatedbackpacks.automation.conduit.ConduitKind;

/** Directions are relative to the engine: input fills the machine and output drains it. */
public enum EngineSideMode {
    DISABLED(false, false), INPUT(true, false), OUTPUT(false, true), BOTH(true, true);

    private final boolean input, output;

    EngineSideMode(boolean input, boolean output) { this.input = input; this.output = output; }
    public boolean allowsInput() { return input; }
    public boolean allowsOutput() { return output; }
    public EngineSideMode next(ConduitKind kind) {
        if (kind == ConduitKind.ENERGY) return this == OUTPUT ? DISABLED : OUTPUT;
        return values()[(ordinal() + 1) % values().length];
    }
}
