package com.kartaguez.pocoma.runtime.consumption.v3.dev;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionWorkerSettings;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** Technical effect only. Never a Pot/Balance/Result projection or an Identity authority. */
public final class V3TechnicalReadParticipant implements ConsumptionParticipant {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final ConsumptionDefinition definition;

    public V3TechnicalReadParticipant(DataSource source, ConsumptionDefinition definition) {
        if (!V3ConsumptionWorkerSettings.probeDefinitions().contains(definition))
            throw new IllegalArgumentException("only exact technical definitions allowed");
        this.source = Objects.requireNonNull(source);
        this.jdbc = new JdbcTemplate(source);
        this.definition = definition;
    }

    public Optional<Prepared> prepare(UUID id) {
        return Optional.of(
                new Prepared() {
                    public void finalizeIn(SlotCompletion completion) {
                        SpringAttemptTransactions.guarded(
                                source,
                                () -> {
                                    jdbc.update(
                                            "insert into"
                                                + " i5_read_probe_effect(consumption_id,consumable_id,value)"
                                                + " values (?,?,'technical')",
                                            definition.id().value(),
                                            id);
                                    return null;
                                });
                        completion.complete();
                        SpringAttemptTransactions.closePublication(source);
                    }

                    public void requireCompletionReady() {
                        SpringAttemptTransactions.guarded(
                                source,
                                () -> {
                                    if (count(id) != 1)
                                        throw new IllegalStateException("technical effect missing");
                                    return null;
                                });
                    }
                });
    }

    private int count(UUID id) {
        return jdbc.queryForObject(
                "select count(*) from i5_read_probe_effect where consumption_id=? and"
                        + " consumable_id=? and value='technical'",
                Integer.class,
                definition.id().value(),
                id);
    }

    public RecoveryObservation observe(UUID id, RecoveryReadContext context) {
        SpringAttemptTransactions.requireRead(source, context);
        return count(id) == 1 ? RecoveryObservation.COMPLETED : RecoveryObservation.NOT_COMPLETED;
    }
}
