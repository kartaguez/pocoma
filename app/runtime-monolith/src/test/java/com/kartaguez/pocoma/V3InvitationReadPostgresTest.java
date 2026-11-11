package com.kartaguez.pocoma;
import com.kartaguez.pocoma.compose.polling.materialize.v3.V3ReadParticipants;
import com.kartaguez.pocoma.infra.security.spring.v3.V3InvitationConfiguration;
import com.kartaguez.pocoma.infra.security.spring.v3.InvitationCryptography;
import com.kartaguez.pocoma.infra.postgres.source.v3.*;
import com.kartaguez.pocoma.infra.postgres.core.v3.*;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.DiscoveryEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.WorkerCapacity;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.v3.Invitation;
import com.kartaguez.pocoma.infra.postgres.write.v3.*;
import com.kartaguez.pocoma.infra.postgres.write.v3.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.*;
import java.util.*;

import javax.sql.DataSource;

class V3InvitationReadPostgresTest extends V3InvitationFixture {
    static SpringAttemptTransactions owner(DataSource source) {
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        return new SpringAttemptTransactions(source, manager, 10);
    }

    Invitation source(UUID event) {
        var ds = f.worker.write.getDataSource();
        var owner = owner(ds);
        return owner.read(c -> new JdbcInvitationSource(ds).load(event, c));
    }

    @Test
    void authenticatedEncryptionAndOldKeyRestoration() throws Exception {
        startPot();
        UUID id = create(Instant.parse("2030-01-01T00:00:00Z"));
        link(id);
        var i =
                source(
                        f.worker.write.queryForObject(
                                "select event_id from v3_invitation_event", UUID.class));
        byte[] old =
                Base64.getDecoder()
                        .decode(
                                f.http.context
                                        .getEnvironment()
                                        .getRequiredProperty("pocoma.v3.invitation.keys.fixture"));
        byte[] fresh = new byte[32];
        new java.security.SecureRandom().nextBytes(fresh);
        var previous = new InvitationCryptography("fixture", Map.of("fixture", old));
        var restored = new InvitationCryptography("new", Map.of("fixture", old, "new", fresh));
        assertTrue(
                previous.decrypt(i).equals(restored.decrypt(i)),
                "Old protected link did not restore");
        var missingOld = new InvitationCryptography("new", Map.of("new", fresh));
        assertThrows(IllegalStateException.class, () -> missingOld.decrypt(i));
        var substituted =
                new Invitation(
                        UUID.randomUUID(),
                        i.version(),
                        i.creationRequestId(),
                        i.creatorIssuer(),
                        i.creatorSubject(),
                        i.potId(),
                        i.expiresAt(),
                        i.tokenHash(),
                        i.protectedLink());
        assertThrows(IllegalStateException.class, () -> restored.decrypt(substituted));
        String cipher = i.protectedLink();
        int index = cipher.lastIndexOf('.') + 2;
        var corrupt =
                new Invitation(
                        i.invitationId(),
                        i.version(),
                        i.creationRequestId(),
                        i.creatorIssuer(),
                        i.creatorSubject(),
                        i.potId(),
                        i.expiresAt(),
                        i.tokenHash(),
                        cipher.substring(0, index)
                                + (cipher.charAt(index) == 'A' ? 'B' : 'A')
                                + cipher.substring(index + 1));
        assertThrows(IllegalStateException.class, () -> restored.decrypt(corrupt));
        assertNull(
                f.worker
                        .contexts
                        .get(1)
                        .getEnvironment()
                        .getProperty("pocoma.v3.invitation.current-key"),
                "Projection root has no decryption key configuration");
    }

    @Test
    void monotoneDuplicateAndDivergence() throws Exception {
        startPot();
        UUID id = create(Instant.parse("2030-01-01T00:00:00Z"));
        link(id);
        UUID event =
                f.worker.write.queryForObject(
                        "select event_id from v3_invitation_event", UUID.class);
        var first = source(event);
        var ds = f.worker.read.getDataSource();
        var owner = owner(ds);
        var store = new com.kartaguez.pocoma.infra.postgres.read.v3.JdbcInvitationStore(ds);
        var crypto = V3InvitationConfiguration.keys(f.http.context.getEnvironment());
        var unsealed =
                new Invitation(
                        first.invitationId(),
                        2,
                        id,
                        first.creatorIssuer(),
                        first.creatorSubject(),
                        first.potId(),
                        first.expiresAt(),
                        first.tokenHash(),
                        "unsealed");
        var next =
                new Invitation(
                        first.invitationId(),
                        2,
                        id,
                        first.creatorIssuer(),
                        first.creatorSubject(),
                        first.potId(),
                        first.expiresAt(),
                        first.tokenHash(),
                        crypto.encrypt(unsealed, crypto.decrypt(first)));
        owner.write(
                () -> {
                    store.put(next);
                    return null;
                });
        owner.write(
                () -> {
                    store.put(first);
                    store.put(next);
                    return null;
                });
        assertEquals(next, owner.read(c -> store.byRequest(id).orElseThrow()));
        assertTrue(crypto.decrypt(first).equals(crypto.decrypt(next)), "Protected link changed");
        var divergent =
                new Invitation(
                        first.invitationId(),
                        2,
                        id,
                        first.creatorIssuer(),
                        first.creatorSubject(),
                        first.potId(),
                        first.expiresAt().plusSeconds(1),
                        first.tokenHash(),
                        next.protectedLink());
        assertThrows(
                RuntimeException.class,
                () ->
                        owner.write(
                                () -> {
                                    store.put(divergent);
                                    return null;
                                }));
        assertEquals(next, owner.read(c -> store.byRequest(id).orElseThrow()));
    }

    @Test
    void reconstructOnFreshReadWithoutProducer() throws Exception {
        startPot();
        UUID a = create(Instant.parse("2030-01-01T00:00:00Z")),
                b = create(Instant.parse("2031-01-01T00:00:00Z"));
        link(a);
        link(b);
        var expected =
                f.worker.read.queryForList(
                        "select"
                            + " invitation_id,version,creation_request_id,creator_issuer,creator_subject,pot_id,expires_at,token_hash,protected_link"
                            + " from read_current_invitation order by invitation_id");
        for (var context : new ArrayList<>(f.worker.contexts)) f.worker.drain(context);
        try (var fresh = new ReadMonolithI8Fixture(PG)) {
            Flyway.configure()
                    .dataSource(fresh.readUrl, PG.getUsername(), PG.getPassword())
                    .schemas(fresh.schema)
                    .defaultSchema(fresh.schema)
                    .locations(
                            "classpath:db/v3-consumption-read/migration",
                            "classpath:db/v3-read-business/migration",
                            "classpath:db/v3-read-invitation/migration")
                    .placeholders(
                            Map.of(
                                    "expectedDatabase",
                                    "i5_read",
                                    "expectedSchema",
                                    fresh.schema,
                                    "expectedAdmin",
                                    PG.getUsername(),
                                    "expectedRuntime",
                                    fresh.readRole))
                    .load()
                    .migrate();
            assertEquals(
                    0,
                    fresh.read.queryForObject(
                            "select count(*) from read_current_invitation", Integer.class));
            var write = f.worker.write.getDataSource();
            var read = fresh.read.getDataSource();
            var sourceOwner = owner(write);
            var effects = owner(read);
            var participant = V3ReadParticipants.invitation(write, read);
            var definition = participant.definition();
            var settings = LifecycleSettings.pilot();
            var discoverySettings =
                    new DiscoverySettings(
                            128, 32, 32, 64, Duration.ofMillis(500), Duration.ofSeconds(2));
            var slots = new JdbcConsumptionSlotStore(read);
            var ownership = new JdbcPartitionOwnershipStore(read);
            var admissions = new JdbcDiscoveryAdmissionStore(read, discoverySettings);
            var source =
                    new JdbcEventDiscoveryReferences(write, definition.family(), discoverySettings);
            var engine =
                    new ConsumptionEngine(
                            definition,
                            effects,
                            slots,
                            participant.participant(),
                            sourceOwner,
                            Optional.of(source),
                            r -> true,
                            admissions,
                            effects,
                            slots,
                            ownership,
                            settings,
                            new WorkerCapacity(1, Map.of(definition.id(), 1)),
                            failure -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                            null,
                            ConsumptionObservation.noop());
            var discovery =
                    new DiscoveryEngine(
                            definition,
                            effects,
                            ownership,
                            admissions,
                            discoverySettings,
                            sourceOwner,
                            source,
                            r -> true);
            for (int p = 3; p >= 0; p--) {
                var lease = engine.acquirePartition(p).orElseThrow();
                discovery.discoverEvents(lease);
                var ids =
                        fresh.read.queryForList(
                                "select consumable_id from consumption_slot where consumption_id=?"
                                    + " and partition_id=? order by consumable_id desc",
                                UUID.class,
                                definition.id().value(),
                                p);
                for (UUID id : ids) {
                    var claims = engine.acquire(lease, List.of(id)).claims();
                    assertEquals(1, claims.size());
                    var attempt = engine.execute(claims.getFirst());
                    assertEquals(
                            ConsumptionProtocol.Status.COMMITTED,
                            attempt.status(),
                            () -> "Replay: " + attempt.cause());
                }
                discovery.discoverEvents(lease);
            }
            assertEquals(
                    expected,
                    fresh.read.queryForList(
                            "select"
                                + " invitation_id,version,creation_request_id,creator_issuer,creator_subject,pot_id,expires_at,token_hash,protected_link"
                                + " from read_current_invitation order by invitation_id"));
            assertEquals(
                    0,
                    fresh.read.queryForObject(
                            "select count(*) from consumption_slot where consumption_id=?",
                            Integer.class,
                            definition.id().value()));
            assertEquals(
                    4,
                    fresh.read.queryForObject(
                            "select count(*) from discovery_checkpoint where consumption_id=?",
                            Integer.class,
                            definition.id().value()));
        }
    }
}
