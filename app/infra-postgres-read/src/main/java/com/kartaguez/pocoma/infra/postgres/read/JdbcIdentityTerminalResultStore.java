package com.kartaguez.pocoma.infra.postgres.read;

import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.projection.IdentityTerminalResult;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.projection.IdentityTerminalResultStore;
import com.kartaguez.pocoma.port.projection.ReadEvidence;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcIdentityTerminalResultStore implements IdentityTerminalResultStore {
    private static final ReadAvailability.Family FAMILY = ReadAvailability.Family.IDENTITY_REQUEST_RESULT_AVAILABLE;

    private final ReadJdbc db;
    private final ReadAvailability availability;

    public JdbcIdentityTerminalResultStore(DataSource source, SpringAttemptTransactions owner) {
        this(source, owner, false);
    }

    public JdbcIdentityTerminalResultStore(
            DataSource source, SpringAttemptTransactions owner, boolean publishAvailability) {
        db = new ReadJdbc(source, owner);
        availability = new ReadAvailability(db, publishAvailability);
    }

    @Override
    public Optional<IdentityTerminalResult> find(UUID id) {
        return db.readOwned(() -> load(id));
    }

    @Override
    public boolean insertOrVerify(IdentityTerminalResult value) {
        return db.write(() -> {
            var target = value.targetEui();
            boolean inserted = db.sql.update("""
                    insert into read_identity_request_result(
                        request_id, operation, requester_issuer, requester_subject,
                        write_outcome, target_issuer, target_subject, target_version, rejection)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (request_id) do nothing
                    """, value.requestId(), value.operation(), value.requesterEui().issuer(),
                    value.requesterEui().subject(), value.writeOutcome(),
                    target == null ? null : target.issuer(), target == null ? null : target.subject(),
                    value.targetVersion(), value.rejection()) == 1;
            ReadJdbc.same(value, load(value.requestId()).orElse(null));
            return inserted;
        });
    }

    @Override
    public void requireCompletionReady(IdentityTerminalResult value, boolean inserted) {
        db.write(() -> {
            ReadJdbc.same(value, load(value.requestId()).orElse(null));
            availability.requireHistoricalEvent(FAMILY, value.requestId(), null, !inserted);
            return null;
        });
    }

    @Override
    public ReadEvidence evidence(IdentityTerminalResult expected, RecoveryReadContext context) {
        return db.read(context, () -> {
            var actual = load(expected.requestId());
            Optional<Boolean> event = availability.enabled()
                    ? availability.historicalEventPositionValid(FAMILY, expected.requestId(), null)
                    : Optional.empty();
            if (actual.isEmpty()) {
                return event.isPresent() ? ReadEvidence.DIVERGENT : ReadEvidence.MISSING;
            }
            return expected.equals(actual.get()) && (!availability.enabled() || event.orElse(false))
                    ? ReadEvidence.SATISFIED : ReadEvidence.DIVERGENT;
        });
    }

    @Override
    public void closePublication(UUID requestId, boolean inserted) {
        availability.closeAndPublish(ReadAvailability.Publication.historical(
                FAMILY, requestId, null, availability.enabled() && inserted));
    }

    private Optional<IdentityTerminalResult> load(UUID id) {
        return db.sql.query("""
                select operation, requester_issuer, requester_subject, write_outcome,
                       target_issuer, target_subject, target_version, rejection
                from read_identity_request_result where request_id = ?
                """, (row, index) -> {
            String targetIssuer = row.getString(5);
            return new IdentityTerminalResult(id, row.getString(1),
                    new ExternalIdentity(row.getString(2), row.getString(3)), row.getString(4),
                    targetIssuer == null ? null : new ExternalIdentity(targetIssuer, row.getString(6)),
                    row.getObject(7, Long.class), row.getString(8));
        }, id).stream().findFirst();
    }
}
