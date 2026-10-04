package com.anrilogger.tracking;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;

/** One shared allowance per player action, across all synchronous and delayed branches. */
public final class PropagationBudget {
    public static final int LIMIT = 10;
    private static final int MAX_REMEMBERED = 100_000;
    private static final LinkedHashMap<String, WeakReference<PropagationBudget>> ACTIVE = new LinkedHashMap<>(128, 0.75f, true);
    private static final PropagationBudget EXHAUSTED = new PropagationBudget(0);
    private int remaining;

    private PropagationBudget(int remaining) { this.remaining = remaining; }

    public static synchronized PropagationBudget start(String causeId) {
        PropagationBudget budget = new PropagationBudget(LIMIT);
        ACTIVE.put(causeId, new WeakReference<>(budget));
        while (ACTIVE.size() > MAX_REMEMBERED) ACTIVE.pollFirstEntry();
        return budget;
    }

    /** Never replenish a budget from NBT: different entities may contain stale copies. */
    public static synchronized PropagationBudget restore(String causeId) {
        WeakReference<PropagationBudget> reference = ACTIVE.get(causeId);
        PropagationBudget budget = reference == null ? null : reference.get();
        if (budget == null) ACTIVE.remove(causeId);
        return budget == null ? EXHAUSTED : budget;
    }

    public static synchronized void clear() { ACTIVE.clear(); }
    public boolean available() { return remaining > 0; }
    public boolean recordBlock() {
        if (remaining == 0) return false;
        remaining--;
        return true;
    }
}
