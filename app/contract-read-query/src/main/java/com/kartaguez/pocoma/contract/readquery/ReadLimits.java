package com.kartaguez.pocoma.contract.readquery;

/** Server-owned internal limits; transport limits and their public values belong to L5. */
public record ReadLimits(int expenseBatch, int navigationPage, long responseBudget) {
    public ReadLimits {
        if (expenseBatch < 1 || navigationPage < 1 || responseBudget < 1)
            throw new IllegalArgumentException("positive READ limits required");
    }
    /** Pure validation shared by reader and facade, before any quota acquisition. */
    public boolean validBatch(java.util.List<ReadPotResult.ExpenseRef> references) {
        return references != null && !references.isEmpty() && references.size() <= expenseBatch
                && references.stream().allMatch(java.util.Objects::nonNull)
                && references.stream().map(ReadPotResult.ExpenseRef::id).distinct().count() == references.size();
    }
    public boolean validPage(int limit) { return limit >= 1 && limit <= navigationPage; }
}
