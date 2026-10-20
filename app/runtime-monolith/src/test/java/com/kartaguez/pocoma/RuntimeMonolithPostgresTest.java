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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void actualCommandAndRegistrationOwnersSharePrimaryPhysicalTransaction(boolean active) {
        var application=new SpringApplication(PocomaRuntimeMonolithApplication.class,TestJwtConfiguration.class);
        try(var context=application.run("--server.port=0", "--spring.datasource.url="+POSTGRES.getJdbcUrl(),
                "--spring.datasource.username="+POSTGRES.getUsername(), "--spring.datasource.password="+POSTGRES.getPassword(),
                "--pocoma.registration-consumption.enabled=false", "--pocoma.registration-result-consumption.enabled=false",
                "--pocoma.binding-consumption.enabled=false", "--pocoma.command-consumption.enabled=false",
                "--pocoma.command-result-consumption.enabled=false", "--pocoma.event-consumption.enabled=false",
                "--pocoma.projection-task-consumption.enabled=false")) {
            JdbcTemplate sql=context.getBean(JdbcTemplate.class);
            sql.update("update event_sequencing_protocol set mode='PREPARED'");
            sql.execute("truncate users,external_identity_binding_streams,recorded_commands,registration_requests,consumption_slots,"
                    +"business_event_outbox,command_terminal_events,external_identity_binding_facts,user_created_facts cascade");
            sql.update("update event_sequence_authorities set last_position=0");
            sql.update("update event_sequencing_protocol set mode=?",active?"ACTIVE":"PREPARED");
            sql.execute("create table if not exists step4d_monolith(ord bigserial,phase text,pid int,xid bigint)");
            sql.execute("truncate step4d_monolith");
            sql.execute("create or replace function step4d_monolith_trace() returns trigger language plpgsql as $$ begin "
                    +"insert into step4d_monolith(phase,pid,xid) values(TG_TABLE_NAME||':'||TG_OP,pg_backend_pid(),txid_current());return new;end $$");
            for(String table:java.util.List.of("pot_global_versions","pot_headers","pot_version_metadata","users","external_identity_binding_streams","external_identity_binding_occurrences",
                    "external_identities","user_created_facts","external_identity_binding_facts","registration_outcomes",
                    "business_event_outbox","command_outcomes","command_terminal_events","consumption_slots","consumption_claims",
                    "event_sequence_authorities","business_event_positions","command_terminal_event_positions","binding_fact_positions","user_created_fact_positions")) {
                sql.execute("drop trigger if exists step4d_monolith on "+table);
                sql.execute("create trigger step4d_monolith after insert or update on "+table+" for each row execute function step4d_monolith_trace()");
            }
            var managers=context.getBeansOfType(org.springframework.transaction.PlatformTransactionManager.class);
            assertEquals(1,managers.size());var manager=(org.springframework.orm.jpa.JpaTransactionManager)managers.values().iterator().next();
            assertEquals(1,context.getBeansOfType(jakarta.persistence.EntityManagerFactory.class).size());
            assertSame(context.getBean(jakarta.persistence.EntityManagerFactory.class),manager.getEntityManagerFactory());
            assertSame(sql.getDataSource(),manager.getDataSource());
            var protocol=context.getBean(com.kartaguez.pocoma.runtime.publication.PrimaryEventProtocol.class);
            var identity=new com.kartaguez.pocoma.domain.useridentity.ExternalIdentity("monolith4d",java.util.UUID.randomUUID().toString());
            var requestId=java.util.UUID.randomUUID();
            protocol.runner().runInTransaction(()->{context.getBean(com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.JdbcRegistrationRequestStore.class)
                    .insert(new com.kartaguez.pocoma.contracts.registration.RegistrationRequest(requestId,identity,"{}",Instant.now()));return null;});
            var input=new com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput(
                    new com.kartaguez.pocoma.domain.consumption.claim.WorkerId("monolith4d"),
                    new com.kartaguez.pocoma.domain.consumption.claim.ClaimLease(Duration.ofSeconds(30)),
                    new com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget(10,1));
            context.getBean("registrationOrchestrator",com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator.class).run(input);
            assertEquals(1,sql.queryForObject("select count(*) from registration_outcomes where outcome_type='REGISTERED'",Integer.class));
            assertAtomicMonolithTrace(sql,"external_identity_binding_streams:INSERT");
            assertEquals(active?2:0,sql.queryForObject("select count(*) from event_position",Integer.class));
            var user=sql.queryForObject("select user_id from users",java.util.UUID.class);
            var binding=sql.queryForObject("select binding_id from external_identities",java.util.UUID.class);
            sql.execute("truncate step4d_monolith");
            var command=new com.kartaguez.pocoma.contracts.command.RecordedCommand(new com.kartaguez.pocoma.contracts.command.CommandId(java.util.UUID.randomUUID()),
                    new com.kartaguez.pocoma.contracts.command.CommandType("POT_CREATE_V1"),
                    "{\"label\":\"monolith4d\",\"creatorId\":\""+user+"\"}",Instant.now(),
                    new com.kartaguez.pocoma.contracts.command.TargetCommandEnvelope(identity,new com.kartaguez.pocoma.domain.useridentity.BindingId(binding),
                            new com.kartaguez.pocoma.contracts.command.CommandAuthenticationEvidence(Set.of("pocoma:pot:create"),Instant.now().plusSeconds(60))));
            protocol.runner().runInTransaction(()->context.getBean(com.kartaguez.pocoma.engine.admit.command.port.out.RecordedCommandInsertionPort.class).insert(command));
            context.getBean("commandConsumptionOrchestrator",com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator.class).run(input);
            assertEquals("APPLIED",sql.queryForObject("select outcome_type from command_outcomes",String.class));
            assertAtomicMonolithTrace(sql,"pot_global_versions:INSERT");
            assertEquals(active?4:0,sql.queryForObject("select count(*) from event_position",Integer.class));
            assertEquals(2,sql.queryForObject("select count(*) from consumption_slots where status='DONE'",Integer.class));
            assertEquals(active?4L:0L,sql.queryForObject("select sum(last_position) from event_sequence_authorities",Long.class));
            sql.update("update event_sequencing_protocol set mode='PREPARED'");
        }
    }
    private static void assertAtomicMonolithTrace(JdbcTemplate sql,String firstEffect) {
        assertEquals(1,sql.queryForObject("select count(distinct (pid,xid)) from step4d_monolith where ord >= "
                +"(select min(ord) from step4d_monolith where phase=?)",Integer.class,firstEffect));
        assertTrue(sql.queryForObject("select count(*) from step4d_monolith where phase='consumption_claims:UPDATE'",Integer.class)>0);
        var phases=sql.queryForList("select phase from step4d_monolith order by ord",String.class);
        if(phases.contains("event_sequence_authorities:UPDATE"))
            assertTrue(phases.lastIndexOf("consumption_claims:UPDATE")<phases.indexOf("event_sequence_authorities:UPDATE"));
        System.out.println("STEP4D MONOLITH "+sql.queryForList("select * from step4d_monolith order by ord"));
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
        assertEquals(31, jdbc.queryForObject(
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
