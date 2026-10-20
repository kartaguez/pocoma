package com.kartaguez.pocoma.runtime.command.consumption.eventprotocol;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.*;
import com.kartaguez.pocoma.port.transaction.PublishedEvents;

/** Same actual runtime root, without the explicit step4B test import. */
@SpringBootTest(classes = PocomaCommandConsumptionWorkerApplication.class,
        properties = "pocoma.command-consumption.enabled=false")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrimaryEventProtocolDormancyPostgresTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", PG::getJdbcUrl);
        r.add("spring.datasource.username", PG::getUsername);
        r.add("spring.datasource.password", PG::getPassword);
    }
    @Autowired ApplicationContext context;
    @Test void productionScanDoesNotWireProtocolOrTestConfiguration() {
        assertTrue(context.getBeansOfType(PrimaryEventTransactionRunner.class).isEmpty());
        assertTrue(context.getBeansOfType(PrimaryEventTransactionSuffix.class).isEmpty());
        assertTrue(context.getBeansOfType(PublishedEvents.class).isEmpty());
        assertTrue(context.getBeansOfType(PrimaryEventProtocolTestConfiguration.class).isEmpty());
        var managers = context.getBeansOfType(PlatformTransactionManager.class);
        assertEquals(1, managers.size());
        assertEquals(JpaTransactionManager.class, managers.values().iterator().next().getClass());
    }
}
