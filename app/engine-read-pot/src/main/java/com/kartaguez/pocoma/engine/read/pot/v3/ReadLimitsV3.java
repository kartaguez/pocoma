package com.kartaguez.pocoma.engine.read.pot.v3;

/** Server-owned internal limits; transport limits and their public values belong to L5. */
public record ReadLimitsV3(int expenseBatch, int navigationPage, long responseBudget) {
    public ReadLimitsV3 {
        if (expenseBatch < 1 || navigationPage < 1 || responseBudget < 1)
            throw new IllegalArgumentException("positive READ limits required");
    }
    /** Pure validation shared by reader and facade, before any quota acquisition. */
    public boolean validBatch(java.util.List<ReadPotV3Result.ExpenseRef> references) {
        return references != null && !references.isEmpty() && references.size() <= expenseBatch
                && references.stream().allMatch(java.util.Objects::nonNull)
                && references.stream().map(ReadPotV3Result.ExpenseRef::id).distinct().count() == references.size();
    }
    public boolean validPage(int limit) { return limit >= 1 && limit <= navigationPage; }
}
