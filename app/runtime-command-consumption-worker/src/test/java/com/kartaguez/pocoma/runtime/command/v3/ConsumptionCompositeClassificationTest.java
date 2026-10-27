package com.kartaguez.pocoma.runtime.command.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.v3.InconsistentPotFactsException;
import com.kartaguez.pocoma.engine.consumption.v3.port.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionLifecycleComposition;

import jakarta.persistence.PersistenceException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.*;

/** Literal R5 expectations, independent of the traversal and without database fixtures. */
class ConsumptionCompositeClassificationTest {
    static void kind(FailureKind expected, Throwable cause) {
        assertEquals(expected, V3ConsumptionLifecycleComposition.classify(cause));
    }

    @Test
    void isolatedBusinessTechnicalAndPersistenceFaults() {
        kind(FailureKind.TECHNICAL, new ExecutePotCommand.Collision());
        kind(FailureKind.TECHNICAL, new InconsistentPotFactsException("invalid facts"));
        kind(FailureKind.TECHNICAL, new NullPointerException("actual bug"));
        kind(FailureKind.TECHNICAL, new IllegalArgumentException("corrupt durable payload"));
        kind(FailureKind.SQL_OR_JPA, new PersistenceException("actual persistence fault"));
        kind(FailureKind.SQL_OR_JPA, new DataAccessResourceFailureException("actual SQL fault"));
        for (String state : List.of("23514", "40001", "40P01", "22012"))
            kind(FailureKind.SQL_OR_JPA, new SQLException("server fault", state));
    }

    @Test
    void connectionAndStatementCompletionUnknownNeverCount() {
        for (String state :
                List.of("08000", "08001", "08003", "08004", "08006", "08007", "08P01", "40003"))
            kind(FailureKind.NON_COUNTABLE, new SQLException("uncertain", state));
    }

    @Test
    void excludedWinsAcrossMixedCauseAndSuppressedInBothOrders() {
        for (String state : List.of("08006", "40003")) {
            var countable = new IllegalStateException("actual application fault");
            countable.addSuppressed(new PersistenceException(new SQLException("uncertain", state)));
            kind(FailureKind.NON_COUNTABLE, countable);
            var excluded = new SQLException("uncertain", state);
            excluded.addSuppressed(new SQLException("constraint", "23514"));
            kind(FailureKind.NON_COUNTABLE, new PersistenceException(excluded));
        }
        var sql = new SQLException("constraint", "23514");
        sql.addSuppressed(
                new org.springframework.transaction.IllegalTransactionStateException(
                        "bad composition"));
        kind(FailureKind.NON_COUNTABLE, sql);
        var bug = new NullPointerException("bug");
        bug.addSuppressed(
                new org.springframework.beans.BeanInstantiationException(
                        Object.class, "bad configuration"));
        kind(FailureKind.NON_COUNTABLE, bug);
    }

    @Test
    void jdbcNextExceptionExclusionWinsInBothOrdersWithCycles() {
        for (boolean excludedFirst : List.of(false, true)) {
            var countable = new SQLException("constraint", "23514");
            var excluded = new SQLException("uncertain", "08006");
            var first = excludedFirst ? excluded : countable;
            var second = excludedFirst ? countable : excluded;
            first.setNextException(second);
            second.setNextException(first);
            kind(FailureKind.NON_COUNTABLE, first);
        }
    }

    @Test
    void springJpaCompositePreservesExcludedCauses() {
        var jdbc = new SQLException("constraint", "23514");
        var jpa = new PersistenceException("flush", jdbc);
        var transaction = new TransactionSystemException("commit", jpa);
        transaction.addSuppressed(new SQLException("uncertain", "40003"));
        kind(FailureKind.NON_COUNTABLE, transaction);
        kind(FailureKind.SQL_OR_JPA, new UnexpectedRollbackException("rollback", jpa));
    }

    @Test
    void springOriginalApplicationExceptionParticipatesInClassification() {
        var wrapper =
                new TransactionSystemException("rollback", new SQLException("constraint", "23514"));
        wrapper.initApplicationException(
                new SQLException("original transport uncertainty", "08006"));
        kind(FailureKind.NON_COUNTABLE, wrapper);
        var applicationOnly = new TransactionSystemException("rollback wrapper");
        applicationOnly.initApplicationException(new SQLException("actual server fault", "22012"));
        kind(FailureKind.SQL_OR_JPA, applicationOnly);
    }

    @Test
    void cancellationInterruptionAndTheirStandardWrappersAloneNeverCount() {
        kind(FailureKind.NON_COUNTABLE, null);
        for (Exception signal : List.of(new CancellationException(), new InterruptedException())) {
            kind(FailureKind.NON_COUNTABLE, signal);
            kind(FailureKind.NON_COUNTABLE, new UnexpectedRollbackException("wrapper", signal));
            kind(FailureKind.NON_COUNTABLE, new CompletionException(signal));
            kind(FailureKind.NON_COUNTABLE, new ExecutionException(signal));
        }
        kind(FailureKind.NON_COUNTABLE, new TransactionSystemException("wrapper only"));
    }

    @Test
    void cancellationDoesNotEraseAProvenCountableFault() {
        var sql = new SQLException("actual server fault", "23514");
        sql.addSuppressed(new CancellationException());
        kind(FailureKind.SQL_OR_JPA, new CompletionException(sql));
        var technical = new NullPointerException("actual bug");
        technical.addSuppressed(new InterruptedException());
        kind(FailureKind.TECHNICAL, new ExecutionException(technical));
    }

    @Test
    void businessTypesWithExcludedCauseRemainNonCountable() {
        for (RuntimeException business :
                List.of(
                        new ExecutePotCommand.Collision(),
                        new InconsistentPotFactsException("invalid facts"))) {
            kind(FailureKind.TECHNICAL, business);
            business.initCause(new SQLException("uncertain", "08006"));
            kind(FailureKind.NON_COUNTABLE, business);
        }
    }

    @Test
    void mixedGraphCyclesTerminateAndSqlPrecedesTechnical() {
        var a = new IllegalStateException("technical A");
        var b = new IllegalStateException("technical B", a);
        a.initCause(b);
        var sql = new SQLException("constraint", "23514");
        b.addSuppressed(sql);
        sql.setNextException(sql);
        kind(FailureKind.SQL_OR_JPA, a);
        sql.addSuppressed(new SQLException("uncertain", "40003"));
        kind(FailureKind.NON_COUNTABLE, a);
    }
}
