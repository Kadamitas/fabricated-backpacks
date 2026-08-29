package com.kadamitas.fabricatedbackpacks.automation.conduit;

/** OFF preserves unrestricted routing; an empty allow list deliberately admits nothing. */
public enum ConduitFilterMode {
    OFF, ALLOW, BLOCK;

    public ConduitFilterMode next() { return values()[(ordinal() + 1) % values().length]; }
}
