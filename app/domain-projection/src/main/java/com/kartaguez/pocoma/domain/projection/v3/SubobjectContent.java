package com.kartaguez.pocoma.domain.projection.v3;

import com.kartaguez.pocoma.domain.pot.v3.*;
import java.util.Objects;
import java.util.UUID;

/** Typed detached business content, excluding source and transport metadata. */
public sealed interface SubobjectContent {
    SubobjectKind kind();
    UUID potId();
    UUID objectId();

    record PotHeaderContent(PotHeader value) implements SubobjectContent {
        public PotHeaderContent { Objects.requireNonNull(value); }
        public SubobjectKind kind() { return SubobjectKind.POT_HEADER; }
        public UUID potId() { return value.potId().value(); }
        public UUID objectId() { return value.potId().value(); }
    }

    record PotDetailsContent(PotDetails value) implements SubobjectContent {
        public PotDetailsContent { Objects.requireNonNull(value); }
        public SubobjectKind kind() { return SubobjectKind.POT_DETAILS; }
        public UUID potId() { return value.potId().value(); }
        public UUID objectId() { return value.potId().value(); }
    }

    record ShareholderHeaderContent(ShareholderHeader value) implements SubobjectContent {
        public ShareholderHeaderContent { Objects.requireNonNull(value); }
        public SubobjectKind kind() { return SubobjectKind.SHAREHOLDER_HEADER; }
        public UUID potId() { return value.potId().value(); }
        public UUID objectId() { return value.shareholderId().value(); }
    }

    record ShareholderDetailsContent(ShareholderDetails value) implements SubobjectContent {
        public ShareholderDetailsContent { Objects.requireNonNull(value); }
        public SubobjectKind kind() { return SubobjectKind.SHAREHOLDER_DETAILS; }
        public UUID potId() { return value.potId().value(); }
        public UUID objectId() { return value.shareholderId().value(); }
    }

    record ShareholderWeightContent(ShareholderWeight value) implements SubobjectContent {
        public ShareholderWeightContent { Objects.requireNonNull(value); }
        public SubobjectKind kind() { return SubobjectKind.SHAREHOLDER_WEIGHT; }
        public UUID potId() { return value.potId().value(); }
        public UUID objectId() { return value.shareholderId().value(); }
    }

    record ExpenseHeaderContent(ExpenseHeader value) implements SubobjectContent {
        public ExpenseHeaderContent { Objects.requireNonNull(value); }
        public SubobjectKind kind() { return SubobjectKind.EXPENSE_HEADER; }
        public UUID potId() { return value.potId().value(); }
        public UUID objectId() { return value.expenseId().value(); }
    }

    record ExpenseDetailsContent(ExpenseDetails value) implements SubobjectContent {
        public ExpenseDetailsContent { Objects.requireNonNull(value); }
        public SubobjectKind kind() { return SubobjectKind.EXPENSE_DETAILS; }
        public UUID potId() { return value.potId().value(); }
        public UUID objectId() { return value.expenseId().value(); }
    }

    record ExpenseFinancialsContent(ExpenseFinancials value) implements SubobjectContent {
        public ExpenseFinancialsContent { Objects.requireNonNull(value); }
        public SubobjectKind kind() { return SubobjectKind.EXPENSE_FINANCIALS; }
        public UUID potId() { return value.potId().value(); }
        public UUID objectId() { return value.expenseId().value(); }
    }
}
