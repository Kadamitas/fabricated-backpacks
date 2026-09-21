package com.kadamitas.fabricatedbackpacks.automation.conduit;

import com.kadamitas.fabricatedbackpacks.platform.transfer.Storage;
import com.kadamitas.fabricatedbackpacks.platform.transfer.StorageView;
import com.kadamitas.fabricatedbackpacks.platform.transfer.TransferVariant;
import com.kadamitas.fabricatedbackpacks.platform.transaction.Transaction;
import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;
import com.kadamitas.fabricatedbackpacks.platform.transfer.EnergyStorage;

import java.util.function.BooleanSupplier;

/** Exact source and destination mutations share one transaction; unsuccessful probes never escape. */
final class ConduitTransfers {
    private ConduitTransfers() {}

    static <T extends TransferVariant<?>> long move(StorageView<T> source, Storage<T> destination, T resource,
                                                   long maximum, BooleanSupplier current, TransactionContext parent) {
        if (maximum <= 0 || !current.getAsBoolean()) return 0;
        try (Transaction transaction = Transaction.open(parent)) {
            long available;
            try (Transaction probe = Transaction.open(transaction)) { available = source.extract(resource, maximum, probe); }
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
        try (Transaction transaction = Transaction.open(parent)) {
            long available;
            try (Transaction probe = Transaction.open(transaction)) { available = source.extract(maximum, probe); }
            if (available <= 0 || available > maximum || !current.getAsBoolean()) return 0;
            long inserted = destination.insert(available, transaction);
            if (inserted <= 0 || inserted > available || !current.getAsBoolean()
                    || source.extract(inserted, transaction) != inserted || !current.getAsBoolean()) return 0;
            transaction.commit();
            return inserted;
        }
    }
}
