package com.kartaguez.pocoma.runtime.publication;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.JdbcEventTransactionSequencer;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.PrimaryEventTransactionRunner;

/** Explicit composition identity shared by owners and their application declaration facade. */
public final class PrimaryEventProtocol {
    private final PrimaryEventTransactionRunner runner;

    public PrimaryEventProtocol(PlatformTransactionManager manager, EntityManagerFactory emf, JdbcTemplate jdbc) {
        this(manager, emf, jdbc, null);
    }

    public PrimaryEventProtocol(PlatformTransactionManager manager, EntityManagerFactory emf, JdbcTemplate jdbc,
            io.micrometer.core.instrument.MeterRegistry registry) {
        if (!(manager instanceof JpaTransactionManager primary)
                || primary.getEntityManagerFactory() != emf || primary.getDataSource() != jdbc.getDataSource())
            throw new IllegalArgumentException("Publication requires the same PRIMARY JPA manager, EMF and JDBC datasource");
        var sequencer = new JdbcEventTransactionSequencer(primary);
        sequencer.verifyCompatibleMode(); // Read-only startup compatibility check; never activates E.
        runner = new PrimaryEventTransactionRunner(primary, registry == null ? sequencer
                : new MeteredEventTransactionSuffix(sequencer, registry));
    }

    public PrimaryEventTransactionRunner runner() { return runner; }
}
