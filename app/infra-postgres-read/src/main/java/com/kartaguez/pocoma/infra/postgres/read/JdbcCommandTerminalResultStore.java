package com.kartaguez.pocoma.infra.postgres.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.projection.CommandTerminalResult;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.projection.CommandTerminalResultStore;
import com.kartaguez.pocoma.port.projection.ReadEvidence;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

public final class JdbcCommandTerminalResultStore implements CommandTerminalResultStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ReadAvailability.Family FAMILY = ReadAvailability.Family.COMMAND_RESULT_AVAILABLE;

    private final ReadJdbc db;
    private final ReadAvailability availability;

    public JdbcCommandTerminalResultStore(DataSource source, SpringAttemptTransactions owner) {
        this(source, owner, false);
    }

    /** Explicit pilot composition on a schema provisioned with the pilot migration. */
    public static JdbcCommandTerminalResultStore availabilityPilot(
            DataSource source, SpringAttemptTransactions owner) {
        return new JdbcCommandTerminalResultStore(source, owner, true);
    }

    public JdbcCommandTerminalResultStore(
            DataSource source, SpringAttemptTransactions owner, boolean publishAvailability) {
        db = new ReadJdbc(source, owner);
        this.availability = new ReadAvailability(db, publishAvailability);
    }

    @Override
    public Optional<CommandTerminalResult> find(UUID requestId) {
        return db.readOwned(() -> load(requestId));
    }

    @Override
    public boolean insertOrVerify(CommandTerminalResult value) {
        return db.write(
                () -> {
                    boolean inserted =
                            db.sql.update(
                                            """
                                            insert into read_command_result (
                                                request_id, operation, requester_issuer, requester_subject,
                                                pot_id, write_outcome, resulting_pot_version, rejection,
                                                created_resources
                                            ) values (?,?,?,?,?,?,?,?,?::jsonb)
                                            on conflict (request_id) do nothing
                                            """,
                                            value.requestId(),
                                            value.operation(),
                                            value.requesterEui().issuer(),
                                            value.requesterEui().subject(),
                                            value.potId(),
                                            value.writeOutcome(),
                                            value.resultingPotVersion(),
                                            value.rejection(),
                                            encodeCreatedResources(value.createdResources()))
                                    == 1;
                    ReadJdbc.same(value, load(value.requestId()).orElse(null));
                    return inserted;
                });
    }

    @Override
    public void requireCompletionReady(CommandTerminalResult value, boolean inserted) {
        db.write(
                () -> {
                    ReadJdbc.same(value, load(value.requestId()).orElse(null));
                    availability.requireHistoricalEvent(FAMILY, value.requestId(), null, !inserted);
                    return null;
                });
    }

    @Override
    public ReadEvidence evidence(CommandTerminalResult expected, RecoveryReadContext context) {
        return db.read(
                context,
                () -> {
                    var actual = load(expected.requestId());
                    Optional<Boolean> publication = availability.enabled()
                            ? availability.historicalEventPositionValid(FAMILY, expected.requestId(), null)
                            : Optional.empty();
                    if (actual.isEmpty()) {
                        return publication.isPresent() ? ReadEvidence.DIVERGENT : ReadEvidence.MISSING;
                    }
                    return expected.equals(actual.get())
                                    && (!availability.enabled() || publication.orElse(false))
                            ? ReadEvidence.SATISFIED
                            : ReadEvidence.DIVERGENT;
                });
    }

    @Override
    public void closePublication(UUID requestId, boolean inserted) {
        availability.closeAndPublish(ReadAvailability.Publication.historical(
                FAMILY, requestId, null, availability.enabled() && inserted));
    }

    private Optional<CommandTerminalResult> load(UUID requestId) {
        return db.sql
                .query(
                        """
                        select operation, requester_issuer, requester_subject, pot_id, write_outcome,
                               resulting_pot_version, rejection, created_resources::text
                        from read_command_result where request_id = ?
                        """,
                        (row, index) ->
                                new CommandTerminalResult(
                                        requestId,
                                        row.getString(1),
                                        new ExternalIdentity(row.getString(2), row.getString(3)),
                                        row.getObject(4, UUID.class),
                                        row.getString(5),
                                        row.getObject(6, Long.class),
                                        row.getString(7),
                                        decodeCreatedResources(row.getString(8))),
                        requestId)
                .stream()
                .findFirst();
    }

    private static String encodeCreatedResources(CommandTerminalResult.CreatedResources created) {
        if (created == null) {
            return null;
        }
        var n = JSON.createObjectNode();
        if (created.potId() != null) {
            n.put("potId", created.potId().toString());
        } else if (created.expenseId() != null) {
            n.put("expenseId", created.expenseId().toString());
        } else if (created.shareholders() != null) {
            var arr = n.withArray("shareholders");
            for (var s : created.shareholders()) {
                arr.addObject()
                        .put("inputIndex", s.inputIndex())
                        .put("shareholderId", s.shareholderId().toString());
            }
        }
        return n.toString();
    }

    private static CommandTerminalResult.CreatedResources decodeCreatedResources(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(raw);
            if (!n.isObject() || n.size() != 1) {
                throw new IllegalArgumentException();
            }
            if (n.has("potId")) {
                return CommandTerminalResult.CreatedResources.pot(
                        UUID.fromString(n.required("potId").textValue()));
            }
            if (n.has("expenseId")) {
                return CommandTerminalResult.CreatedResources.expense(
                        UUID.fromString(n.required("expenseId").textValue()));
            }
            if (n.has("shareholders") && n.required("shareholders").isArray()) {
                var items = new ArrayList<CommandTerminalResult.CreatedShareholder>();
                for (JsonNode s : n.required("shareholders")) {
                    if (!s.isObject()
                            || s.size() != 2
                            || !s.required("inputIndex").isIntegralNumber()) {
                        throw new IllegalArgumentException();
                    }
                    items.add(
                            new CommandTerminalResult.CreatedShareholder(
                                    s.required("inputIndex").intValue(),
                                    UUID.fromString(s.required("shareholderId").textValue())));
                }
                return CommandTerminalResult.CreatedResources.shareholders(items);
            }
            throw new IllegalArgumentException();
        } catch (Exception invalid) {
            throw new IllegalStateException("Invalid READ Command createdResources");
        }
    }
}
