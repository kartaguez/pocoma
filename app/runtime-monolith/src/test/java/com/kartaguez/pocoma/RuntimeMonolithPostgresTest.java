package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionPollingWorker;
import com.kartaguez.pocoma.runtime.binding.BindingConsumptionProperties;
import com.kartaguez.pocoma.runtime.command.consumption.CommandConsumptionProperties;
import com.kartaguez.pocoma.runtime.event.consumption.EventConsumptionProperties;
import com.kartaguez.pocoma.runtime.registration.RegistrationConsumptionProperties;
import com.kartaguez.pocoma.runtime.registrationresult.RegistrationResultConsumptionProperties;
import com.kartaguez.pocoma.runtime.result.CommandResultConsumptionProperties;
import com.kartaguez.pocoma.runtime.task.consumption.CanonicalProjectionTaskProperties;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;

@Testcontainers
class RuntimeMonolithPostgresTest {

    private static final Set<String> WORKER_BEANS = Set.of(
            "registrationWorker",
            "registrationResultWorker",
            "bindingWorker",
            "commandConsumptionPollingWorker",
            "commandResultWorker",
            "consumptionPollingWorker",
            "canonicalProjectionWorker");

    private static final Set<String> LIFECYCLE_BEANS = Set.of(
            "registrationWorkerLifecycle",
            "registrationResultWorkerLifecycle",
            "bindingWorkerLifecycle",
            "commandConsumptionWorkerLifecycle",
            "commandResultWorkerLifecycle",
            "eventConsumptionWorkerLifecycle",
            "canonicalProjectionWorkerLifecycle");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void composesExactlyOneActiveInstanceOfEveryFunctionalWorkerAndStopsCleanly() {
        SpringApplication application = new SpringApplication(
                PocomaRuntimeMonolithApplication.class, TestJwtConfiguration.class);
        ConfigurableApplicationContext context = application.run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword());
        Map<String, ConsumptionPollingWorker> workers = context.getBeansOfType(ConsumptionPollingWorker.class);
        try {
            assertEquals(WORKER_BEANS, workers.keySet());
            assertEquals(Set.of(
                    "transactionRunner", "registrationTransactionRunner",
                    "registrationResultTransactionRunner", "bindingTransactionRunner",
                    "commandConsumptionTransactionRunner", "commandResultTransactionRunner",
                    "consumptionTransactionRunner", "canonicalProjectionTransactions"),
                    context.getBeansOfType(TransactionRunner.class).keySet());
            assertEquals(1, context.getBeansOfType(ObjectMapper.class).size());
            assertSame(context.getBean("webApiObjectMapper"), context.getBean("objectMapper"));
            assertEquals(LIFECYCLE_BEANS, LIFECYCLE_BEANS.stream()
                    .filter(name -> context.getBean(name) instanceof SmartLifecycle).collect(java.util.stream.Collectors.toSet()));
            await(() -> workers.values().stream().allMatch(ConsumptionPollingWorker::isRunning));

            assertWorkerConfiguration(context);
            assertMigrations(context.getBean(JdbcTemplate.class));
            assertTrue(context.containsBean("registrationController"));
            assertTrue(context.containsBean("commandResultController"));
            assertTrue(context.containsBean("currentBindingController"));
            assertTrue(context.containsBean("asyncCommandController"));
            assertTrue(context.containsBean("potQueryController"));
        }
        finally {
            context.close();
        }

        await(() -> workers.values().stream().noneMatch(ConsumptionPollingWorker::isRunning));
        assertFalse(context.isActive());
    }

    private static void assertWorkerConfiguration(ConfigurableApplicationContext context) {
        RegistrationConsumptionProperties registration = context.getBean(RegistrationConsumptionProperties.class);
        RegistrationResultConsumptionProperties registrationResult = context.getBean(RegistrationResultConsumptionProperties.class);
        BindingConsumptionProperties binding = context.getBean(BindingConsumptionProperties.class);
        CommandConsumptionProperties command = context.getBean(CommandConsumptionProperties.class);
        CommandResultConsumptionProperties commandResult = context.getBean(CommandResultConsumptionProperties.class);
        EventConsumptionProperties event = context.getBean(EventConsumptionProperties.class);
        CanonicalProjectionTaskProperties projection = context.getBean(CanonicalProjectionTaskProperties.class);

        assertEquals(Set.of(
                registration.getWorkerId(), registrationResult.getWorkerId(), binding.getWorkerId(),
                command.getWorkerId(), commandResult.getWorkerId(), event.getWorkerId(), projection.getWorkerId()),
                Set.of("monolith-registration-worker", "monolith-registration-result-worker",
                        "monolith-binding-worker", "monolith-command-worker", "monolith-command-result-worker",
                        "monolith-event-worker", "monolith-projection-task-worker"));
        assertSegment(registration.getSegmentIndex(), registration.getSegmentCount());
        assertSegment(registrationResult.getSegmentIndex(), registrationResult.getSegmentCount());
        assertSegment(binding.getSegmentIndex(), binding.getSegmentCount());
        assertSegment(commandResult.getSegmentIndex(), commandResult.getSegmentCount());
        assertSegment(event.getSegmentIndex(), event.getSegmentCount());
        assertSegment(projection.getSegmentIndex(), projection.getSegmentCount());
    }

    private static void assertSegment(int index, int count) {
        assertEquals(0, index);
        assertEquals(1, count);
    }

    private static void assertMigrations(JdbcTemplate jdbc) {
        assertEquals(29, jdbc.queryForObject(
                "select max(version::int) from flyway_schema_history where success", Integer.class));
        assertEquals(14, jdbc.queryForObject(
                "select max(version::int) from pocoma_read.flyway_schema_history where success", Integer.class));
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) {
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwtConfiguration {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("test-subject")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(60))
                    .build();
        }
    }
}
