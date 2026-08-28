package com.kadamitas.fabricatedbackpacks.automation.conduit;

import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConduitBudgetTest {
    @Test
    void simulationAndNestedCommitsRestoreTheSamePhysicalAllowanceOnOuterAbort() {
        var budget = new ConduitBudget();
        try (Transaction outer = Transaction.openOuter()) {
            budget.charge(100, 3, 10, false, outer);
            try (Transaction child = outer.openNested()) {
                budget.charge(100, 2, 10, true, child);
                child.commit();
            }
            assertEquals(3, budget.available(100, 8, 10));
            assertTrue(budget.receivedThisTick(100));
        }
        assertEquals(8, budget.available(100, 8, 10));
        assertFalse(budget.receivedThisTick(100));
    }

    @Test
    void committedWindowStartsAtTheActualTransferAndExpiresAtTheExactDeadline() {
        var budget = new ConduitBudget();
        try (Transaction transaction = Transaction.openOuter()) {
            budget.charge(103, 8, 10, false, transaction);
            transaction.commit();
        }
        assertEquals(0, budget.available(112, 8, 10));
        assertEquals(8, budget.available(113, 8, 10));
        try (Transaction transaction = Transaction.openOuter()) {
            budget.charge(113, 1, 10, true, transaction);
            transaction.commit();
        }
        assertEquals(7, budget.available(113, 8, 10));
        assertTrue(budget.receivedThisTick(113));
        assertFalse(budget.receivedThisTick(114));
    }

    @Test
    void configurationReductionCannotCreateNegativeAllowanceAndClockRollbackResetsWindow() {
        var budget = new ConduitBudget();
        try (Transaction transaction = Transaction.openOuter()) {
            budget.charge(50, 7, 10, false, transaction);
            transaction.commit();
        }
        assertEquals(0, budget.available(51, 3, 10));
        assertEquals(3, budget.available(49, 3, 10));
        assertThrows(IllegalArgumentException.class, () -> budget.available(50, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> budget.available(50, 8, 0));
    }
}
