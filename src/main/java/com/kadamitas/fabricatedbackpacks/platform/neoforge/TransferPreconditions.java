package com.kadamitas.fabricatedbackpacks.platform.neoforge;

public final class TransferPreconditions {
    private TransferPreconditions() {}
    public static void checkNonNegative(long amount) { if (amount < 0) throw new IllegalArgumentException("Negative transfer"); }
    public static void checkNonEmptyNonNegative(Resource resource, long amount) { checkNonNegative(amount); if (resource.isEmpty()) throw new IllegalArgumentException("Empty transfer resource"); }
}
