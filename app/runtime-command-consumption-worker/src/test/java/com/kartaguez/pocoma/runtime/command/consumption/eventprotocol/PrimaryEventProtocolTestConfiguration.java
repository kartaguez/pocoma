package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox.BusinessEventEnvelope;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.outbox.JpaBusinessEventOutboxEntity;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.*;
import com.kartaguez.pocoma.port.transaction.*;
import com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner;

/** Explicit import only. No production writer/owner is substituted. */
@TestConfiguration(proxyBeanMethods = false)
class PrimaryEventProtocolTestConfiguration {
    @Bean SyntheticSuffix step4bSuffix(JdbcTemplate jdbc) { return new SyntheticSuffix(jdbc); }
    @Bean PrimaryEventTransactionRunner step4bRunner(PlatformTransactionManager manager, SyntheticSuffix suffix) {
        return new PrimaryEventTransactionRunner((JpaTransactionManager) manager, suffix);
    }
    @Bean @org.springframework.context.annotation.Primary PublishedEvents step4bDeclarations(PrimaryEventTransactionRunner runner) {
        return runner::recordPublishedEvent;
    }
    @Bean SyntheticWriter step4bWriter(PrimaryEventTransactionRunner runner, JdbcTemplate jdbc) {
        return new SyntheticWriter(runner, jdbc);
    }
    @Bean TransactionRunner step4bConsumptionOwner(PrimaryEventTransactionRunner runner) {
        return new ConsumptionOwnerTransactionRunner(runner, true);
    }

    /** Synthetic insert-only writer: never declares; its caller declares the acceptance. */
    static final class SyntheticWriter {
        private final PrimaryEventTransactionRunner runner;
        private final PrimaryEventInsert inserts;
        private final JdbcTemplate jdbc;
        SyntheticWriter(PrimaryEventTransactionRunner runner, JdbcTemplate jdbc) {
            this.runner = runner; this.jdbc = jdbc; this.inserts = new PrimaryEventInsert(runner);
        }
        EventRef accept(EventFamily family, UUID id) {
            runner.requireOpen();
            if (family == EventFamily.BUSINESS_EVENT) {
                inserts.persistNew(new JpaBusinessEventOutboxEntity(new BusinessEventEnvelope(id,
                        "POT_CREATED", PotId.of(id), id, 1, "{}", null, null, Instant.now())));
            }
            if (jdbc.update("insert into experiment_4b.accepted(family,id) values (?,?)", family.name(), id) != 1)
                throw new IllegalStateException("acceptance must insert exactly one row");
            return new EventRef(family, id);
        }
    }

    /** A probe of the suffix boundary, NOT the 4C sequencer: no counter, allocation or DDL migration. */
    static final class SyntheticSuffix implements PrimaryEventTransactionSuffix {
        static final List<EventFamily> ORDER = List.of(EventFamily.BUSINESS_EVENT,
                EventFamily.COMMAND_TERMINAL_EVENT, EventFamily.BINDING_FACT, EventFamily.USER_CREATED_FACT);
        private final JdbcTemplate jdbc;
        ClosedPublishedEvents last;
        int calls;
        boolean failAfterAssociation;
        SyntheticSuffix(JdbcTemplate jdbc) { this.jdbc = jdbc; }
        @Override public void execute(ClosedPublishedEvents batch) {
            calls++; last = batch;
            for (EventFamily family : ORDER) {
                var events = batch.inFamily(family);
                if (events.isEmpty()) continue;
                jdbc.update("insert into experiment_4b.e_access(family) values (?)", family.name());
                for (EventRef event : events) {
                    // Strict FK proves materialized acceptances; Business JPA INSERT must be visible already.
                    if (family == EventFamily.BUSINESS_EVENT && jdbc.queryForObject(
                            "select count(*) from business_event_outbox where id=?", Integer.class, event.eventId()) != 1)
                        throw new IllegalStateException("JPA intention not materialized before suffix");
                    jdbc.update("insert into experiment_4b.association(family,id) values (?,?)", family.name(), event.eventId());
                    if (failAfterAssociation) throw new IllegalStateException("injected association failure");
                }
            }
        }
    }
}
