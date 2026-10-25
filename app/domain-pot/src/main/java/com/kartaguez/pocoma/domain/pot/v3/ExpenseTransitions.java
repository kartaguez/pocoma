package com.kartaguez.pocoma.domain.pot.v3;

import java.util.*;
import java.time.LocalDate;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

public final class ExpenseTransitions {
    private ExpenseTransitions() {}
    private static void checkHeader(PotFacts pot, Versioned<ExpenseHeader> header, ExpenseId id) {
        TransitionChecks.revision(pot,header.value().potId(),header);
        if (!header.value().expenseId().equals(id)) throw new InconsistentPotFactsException("Mixed Expense");
    }
    private static TransitionResult.Rejection references(PotFacts pot, long observed, ExpenseFinancials requested, Map<ShareholderId,ShareholderReference> facts) {
        var ids = new HashSet<>(requested.participants().keySet()); ids.add(requested.payerId());
        for (var id : ids) TransitionChecks.reference(pot,id,facts.get(id));
        for (var id : ids) { var rejection = TransitionChecks.activeReference(facts.get(id),observed); if (rejection != null) return rejection; }
        return null;
    }
    public static TransitionResult<ExpenseState> create(PotFacts pot, long observed, ExpenseDetails details, ExpenseFinancials financials,
            boolean alreadyExists, Map<ShareholderId,ShareholderReference> facts) {
        if (!details.potId().equals(pot.potId()) || !financials.potId().equals(pot.potId()) || !details.expenseId().equals(financials.expenseId())) throw new InconsistentPotFactsException("Mixed creation");
        var referenceRejection = references(pot,observed,financials,facts);
        var rejection = TransitionChecks.permission(pot,observed,false);
        if (rejection != null) return TransitionChecks.reject(rejection);
        if (alreadyExists) return TransitionChecks.reject(TransitionResult.Rejection.ALREADY_EXISTS);
        if (referenceRejection != null) return TransitionChecks.reject(referenceRejection);
        long v=TransitionChecks.candidate(pot);
        return TransitionResult.Changed.propose(pot.workingVersion(),new ExpenseState(new Versioned<>(1,v,new ExpenseHeader(pot.potId(),details.expenseId(),false)),new Versioned<>(1,v,details),new Versioned<>(1,v,financials)));
    }
    public static TransitionResult<Versioned<ExpenseDetails>> updateDetails(PotFacts pot, long observed, Versioned<ExpenseHeader> header,
            Versioned<ExpenseDetails> current, Label label, LocalDate date) {
        var value=new ExpenseDetails(pot.potId(),current.value().expenseId(),label,date);
        checkHeader(pot,header,current.value().expenseId()); TransitionChecks.revision(pot,current.value().potId(),current);
        var rejection=TransitionChecks.permission(pot,observed,false);
        if (rejection != null) return TransitionChecks.reject(rejection);
        if (header.value().deleted()) return TransitionChecks.reject(TransitionResult.Rejection.DELETED);
        if (header.activeFrom()>observed || current.activeFrom()>observed) return TransitionChecks.reject(TransitionResult.Rejection.OBSOLETE);
        if (current.value().equals(value)) return new TransitionResult.Unchanged<>(pot.workingVersion());
        return TransitionResult.Changed.propose(pot.workingVersion(),TransitionChecks.next(current,value,TransitionChecks.candidate(pot)));
    }
    public static TransitionResult<Versioned<ExpenseFinancials>> updateFinancials(PotFacts pot, long observed, Versioned<ExpenseHeader> header,
            Versioned<ExpenseFinancials> current, ExpenseFinancials requested, Map<ShareholderId,ShareholderReference> facts) {
        checkHeader(pot,header,current.value().expenseId()); TransitionChecks.revision(pot,current.value().potId(),current);
        if (!requested.potId().equals(pot.potId()) || !requested.expenseId().equals(current.value().expenseId())) throw new InconsistentPotFactsException("Mixed Financials");
        var referenceRejection=references(pot,observed,requested,facts);
        var rejection=TransitionChecks.permission(pot,observed,false);
        if (rejection != null) return TransitionChecks.reject(rejection);
        if (header.value().deleted()) return TransitionChecks.reject(TransitionResult.Rejection.DELETED);
        if (header.activeFrom()>observed || current.activeFrom()>observed) return TransitionChecks.reject(TransitionResult.Rejection.OBSOLETE);
        if (referenceRejection != null) return TransitionChecks.reject(referenceRejection);
        if (current.value().equals(requested)) return new TransitionResult.Unchanged<>(pot.workingVersion());
        return TransitionResult.Changed.propose(pot.workingVersion(),TransitionChecks.next(current,requested,TransitionChecks.candidate(pot)));
    }
    public static TransitionResult<Versioned<ExpenseHeader>> delete(PotFacts pot, long observed, ExpenseState current) {
        checkHeader(pot,current.header(),current.details().value().expenseId());
        TransitionChecks.revision(pot,current.details().value().potId(),current.details());
        TransitionChecks.revision(pot,current.financials().value().potId(),current.financials());
        var rejection=TransitionChecks.permission(pot,observed,false);
        if (rejection != null) return TransitionChecks.reject(rejection);
        if (current.header().value().deleted()) return TransitionChecks.reject(TransitionResult.Rejection.DELETED);
        if (current.header().activeFrom()>observed || current.details().activeFrom()>observed || current.financials().activeFrom()>observed) return TransitionChecks.reject(TransitionResult.Rejection.OBSOLETE);
        return TransitionResult.Changed.propose(pot.workingVersion(),TransitionChecks.next(current.header(),new ExpenseHeader(pot.potId(),current.header().value().expenseId(),true),TransitionChecks.candidate(pot)));
    }
}
