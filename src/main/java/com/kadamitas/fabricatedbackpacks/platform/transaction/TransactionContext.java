package com.kadamitas.fabricatedbackpacks.platform.transaction;

/** A mutation scope; only its active innermost transaction may be changed. */
public interface TransactionContext {
    void enlist(SnapshotJournal<?> journal);
}
