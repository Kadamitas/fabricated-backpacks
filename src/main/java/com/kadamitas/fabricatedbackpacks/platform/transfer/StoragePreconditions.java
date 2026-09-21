package com.kadamitas.fabricatedbackpacks.platform.transfer;

public final class StoragePreconditions {
    private StoragePreconditions() {}
    public static void notNegative(long amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative resource amount");
    }
    public static void notBlankNotNegative(TransferVariant<?> resource, long amount) {
        if (resource.isBlank()) throw new IllegalArgumentException("Empty resource identity");
        notNegative(amount);
    }
}
