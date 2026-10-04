package com.anrilogger.tracking;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PropagationBudgetTest {
    @Test void delayedBranchesAndReloadedEntitiesShareTenChanges() {
        String id = UUID.randomUUID().toString();
        PropagationBudget original = PropagationBudget.start(id);
        PropagationBudget firstEntity = PropagationBudget.restore(id);
        for (int i = 0; i < 7; i++) assertTrue(original.recordBlock());
        PropagationBudget secondEntity = PropagationBudget.restore(id);
        for (int i = 0; i < 3; i++) assertTrue(secondEntity.recordBlock());
        assertFalse(firstEntity.recordBlock()); assertFalse(original.available());
        assertFalse(PropagationBudget.restore(id).recordBlock());
        assertTrue(PropagationBudget.start(UUID.randomUUID().toString()).recordBlock());
    }

    @Test void restartOrMissingBudgetCannotReplenishStaleNbt() {
        String id = UUID.randomUUID().toString();
        PropagationBudget original = PropagationBudget.start(id);
        assertTrue(original.recordBlock());
        PropagationBudget.clear();
        assertFalse(PropagationBudget.restore(id).recordBlock());
        assertFalse(PropagationBudget.restore(UUID.randomUUID().toString()).recordBlock());
    }
}
