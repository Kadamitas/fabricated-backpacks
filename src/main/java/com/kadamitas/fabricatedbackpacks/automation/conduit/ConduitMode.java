package com.kadamitas.fabricatedbackpacks.automation.conduit;

/** Directions are relative to the network, never relative to the attached machine. */
public enum ConduitMode {
    EXTRACT(true, false), INSERT(false, true), BOTH(true, true), DISABLED(false, false);

    private final boolean extraction;
    private final boolean insertion;

    ConduitMode(boolean extraction, boolean insertion) {
        this.extraction = extraction;
        this.insertion = insertion;
    }

    public boolean extracts() { return extraction; }
    public boolean inserts() { return insertion; }
    public boolean connects() { return this != DISABLED; }
    public ConduitMode next() { return values()[(ordinal() + 1) % values().length]; }
    public static ConduitMode defaultFor(ConduitKind kind) { return kind == ConduitKind.ENERGY ? BOTH : INSERT; }
}
