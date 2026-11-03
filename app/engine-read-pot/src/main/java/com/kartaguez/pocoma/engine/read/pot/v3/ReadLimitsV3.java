package com.kartaguez.pocoma.engine.read.pot.v3;

/** Server-owned internal limits; transport limits and their public values belong to L5. */
public record ReadLimitsV3(int expenseBatch, int navigationPage, long responseBudget) {
    public ReadLimitsV3 {
        if (expenseBatch < 1 || navigationPage < 1 || responseBudget < 1)
            throw new IllegalArgumentException("positive READ limits required");
    }
}
