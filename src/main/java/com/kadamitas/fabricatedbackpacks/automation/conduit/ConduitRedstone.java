package com.kadamitas.fabricatedbackpacks.automation.conduit;

/** Redstone gates extraction only; a disabled source can still receive into an INSERT/BOTH port. */
public enum ConduitRedstone {
    ALWAYS, HIGH, LOW;

    public boolean permits(boolean powered) { return this == ALWAYS || (this == HIGH) == powered; }
    public ConduitRedstone next() { return values()[(ordinal() + 1) % values().length]; }
}
