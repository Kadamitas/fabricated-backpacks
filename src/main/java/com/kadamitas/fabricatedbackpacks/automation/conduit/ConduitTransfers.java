package com.kadamitas.fabricatedbackpacks.automation.conduit;

import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.TransferVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import team.reborn.energy.api.EnergyStorage;

import java.util.function.BooleanSupplier;

/** Exact source and destination mutations share one transaction; unsuccessful probes never escape. */
final class ConduitTransfers {
    private ConduitTransfers() {}

    static <T extends TransferVariant<?>> long move(StorageView<T> source, Storage<T> destination, T resource,
                                                   long maximum, BooleanSupplier current, TransactionContext parent) {
        if (maximum <= 0 || !current.getAsBoolean()) return 0;
        try (Transaction transaction = Transaction.openNested(parent)) {
            long available;
            try (Transaction probe = transaction.openNested()) { available = source.extract(resource, maximum, probe); }
            if (available <= 0 || available > maximum || !current.getAsBoolean()) return 0;
            long inserted = destination.insert(resource, available, transaction);
            if (inserted <= 0 || inserted > available || !current.getAsBoolean()
                    || source.extract(resource, inserted, transaction) != inserted || !current.getAsBoolean()) return 0;
            transaction.commit();
            return inserted;
        }
    }

    static long move(EnergyStorage source, EnergyStorage destination, long maximum,
                     BooleanSupplier current, TransactionContext parent) {
        if (maximum <= 0 || !current.getAsBoolean()) return 0;
        try (Transaction transaction = Transaction.openNested(parent)) {
            long available;
            try (Transaction probe = transaction.openNested()) { available = source.extract(maximum, probe); }
            if (available <= 0 || available > maximum || !current.getAsBoolean()) return 0;
            long inserted = destination.insert(available, transaction);
            if (inserted <= 0 || inserted > available || !current.getAsBoolean()
                    || source.extract(inserted, transaction) != inserted || !current.getAsBoolean()) return 0;
            transaction.commit();
            return inserted;
        }
    }
}
