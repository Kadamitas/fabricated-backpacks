package com.kadamitas.fabricatedbackpacks.automation.conduit;

/** Independent lanes in one physical bundle. Stable ordinals also index the compact visual state. */
public enum ConduitKind {
    ITEM, FLUID, ENERGY;

    public int mask() { return 1 << ordinal(); }
}
