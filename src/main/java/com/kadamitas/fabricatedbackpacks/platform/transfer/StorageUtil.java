package com.kadamitas.fabricatedbackpacks.platform.transfer;

import java.util.function.Predicate;
import com.kadamitas.fabricatedbackpacks.platform.transaction.Transaction;
import com.kadamitas.fabricatedbackpacks.platform.transaction.TransactionContext;

public final class StorageUtil {
    private StorageUtil() {}
    public static <T> T findStoredResource(Storage<T> storage) { return findStoredResource(storage, value -> true); }
    public static <T> T findStoredResource(Storage<T> storage, Predicate<T> allowed) {
        if (storage != null) for (var view : storage) if (!view.isResourceBlank() && view.getAmount() > 0 && allowed.test(view.getResource())) return view.getResource();
        return null;
    }
    public static <T> long simulateInsert(Storage<T> storage, T resource, long maximum, TransactionContext parent) {
        try (var tx = Transaction.open(parent)) { return storage.insert(resource, maximum, tx); }
    }
    public static <T> long simulateExtract(Storage<T> storage, T resource, long maximum, TransactionContext parent) {
        try (var tx = Transaction.open(parent)) { return storage.extract(resource, maximum, tx); }
    }
    public static <T> long move(Storage<T> from, Storage<T> to, Predicate<T> allowed, long maximum, TransactionContext parent) {
        StoragePreconditions.notNegative(maximum);
        if (from == null || to == null || maximum == 0) return 0;
        long moved = 0;
        for (var view : from) {
            if (view.isResourceBlank() || !allowed.test(view.getResource())) continue;
            var resource = view.getResource();
            try (var tx = Transaction.open(parent)) {
                long available;
                try (var probe = Transaction.open(tx)) { available = view.extract(resource, maximum - moved, probe); }
                long accepted = to.insert(resource, available, tx);
                if (accepted != view.extract(resource, accepted, tx)) continue;
                tx.commit(); moved += accepted;
            }
            if (moved == maximum) break;
        }
        return moved;
    }
}
