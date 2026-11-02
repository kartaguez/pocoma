package com.kartaguez.pocoma.domain.projection.v3;

/** Closed vocabulary; the explicit rank is the shared writer lock order. */
public enum SubobjectKind {
    POT_HEADER(0), POT_DETAILS(1), SHAREHOLDER_HEADER(2), SHAREHOLDER_DETAILS(3),
    SHAREHOLDER_WEIGHT(4), EXPENSE_HEADER(5), EXPENSE_DETAILS(6), EXPENSE_FINANCIALS(7);

    private final int rank;
    SubobjectKind(int rank) { this.rank = rank; }
    public int rank() { return rank; }
}
