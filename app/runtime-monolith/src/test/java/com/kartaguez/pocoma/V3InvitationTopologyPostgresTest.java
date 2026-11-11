package com.kartaguez.pocoma;
import com.kartaguez.pocoma.infra.postgres.core.v3.V3BootstrapGuard;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class V3InvitationTopologyPostgresTest extends V3InvitationFixture {
    @Test
    void invitationPrivilegePreflightsRejectColumnEscalation() throws Exception {
        startRoots();
        var guard = f.http.context.getBean(V3BootstrapGuard.class);
        var write = f.http.context.getBean("writeBusiness", HikariDataSource.class);
        var read = f.http.context.getBean("readBusiness", HikariDataSource.class);
        f.worker.write.execute(
                "grant update(protected_link) on v3_invitation to " + f.worker.writeRole);
        try {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            guard.validateLifecycleSource(
                                    write,
                                    com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition
                                            .Authority.WRITE,
                                    V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY));
        } finally {
            f.worker.write.execute(
                    "revoke update(protected_link) on v3_invitation from " + f.worker.writeRole);
        }
        f.worker.read.execute(
                "grant update(creator_subject) on read_current_invitation to " + f.worker.readRole);
        try {
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            guard.validateLifecycleSource(
                                    read,
                                    com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition
                                            .Authority.READ,
                                    V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY));
        } finally {
            f.worker.read.execute(
                    "revoke update(creator_subject) on read_current_invitation from "
                            + f.worker.readRole);
        }
    }

    @Test
    void targetThreeFourPartitionsConnectionsRenewalAndRestart() throws Exception {
        startPot();
        UUID first = create(Instant.parse("2030-01-01T00:00:00Z"));
        link(first);
        int invitations = 1;
        while (f.worker.write.queryForObject(
                                "select count(distinct"
                                    + " ((consumption_hash_v1('INVITATION_EVENT',event_id)::bigint%4+4)%4))"
                                    + " from v3_invitation_event",
                                Integer.class)
                        < 4
                && invitations < 32) {
            UUID next = create(Instant.parse("2030-01-01T00:00:00Z"));
            link(next);
            invitations++;
        }
        assertEquals(
                4,
                f.worker.write.queryForObject(
                        "select count(distinct"
                            + " ((consumption_hash_v1('INVITATION_EVENT',event_id)::bigint%4+4)%4))"
                            + " from v3_invitation_event",
                        Integer.class));
        final int initialCount = invitations;
        var roots = new ArrayList<>(f.worker.contexts);
        assertEquals(2, roots.size());
        assertEquals(
                3,
                roots.get(0).getBean(V3ConsumptionWorkerSettings.class).readParticipants().size());
        assertEquals(
                4,
                roots.get(1).getBean(V3ConsumptionWorkerSettings.class).readParticipants().size());
        var pools = Collections.newSetFromMap(new IdentityHashMap<HikariDataSource, Boolean>());
        for (var root : roots) {
            var settings = root.getBean(V3ConsumptionWorkerSettings.class);
            assertEquals(4, settings.workerLimit());
            assertEquals(Duration.ofSeconds(180), settings.lifecycle().leaseDuration());
            for (String name : settings.readParticipants())
                assertEquals(List.of(0, 1, 2, 3), settings.partitions().get(name));
            pools.addAll(root.getBeansOfType(HikariDataSource.class).values());
        }
        assertEquals(9, pools.size());
        assertEquals(65, pools.stream().mapToInt(HikariDataSource::getMaximumPoolSize).sum());
        ReadMonolithI8Fixture.await(
                () ->
                        pools.stream()
                                        .mapToInt(
                                                p -> p.getHikariPoolMXBean().getTotalConnections())
                                        .sum()
                                == 65);
        assertEquals(
                65L,
                f.worker.write.queryForObject(
                        "select count(*) from pg_stat_activity where usename in (?,?,?)",
                        Long.class,
                        f.worker.writeRole,
                        f.worker.readRole,
                        f.worker.schema + "_admission"));
        ReadMonolithI8Fixture.await(
                () ->
                        f.worker.read.queryForObject(
                                "select count(*)=28 from partition_ownership where owner_token is"
                                    + " not null and owned_until>clock_timestamp()",
                                Boolean.class));
        var before = ownership();
        ReadMonolithI8Fixture.await(
                () -> {
                    var now = ownership();
                    return now.size() == 28
                            && before.entrySet().stream()
                                    .allMatch(e -> now.get(e.getKey()).after(e.getValue()));
                },
                Duration.ofSeconds(35));
        var tokens =
                f.worker.read.queryForList(
                        "select consumption_id,partition_id,owner_token from partition_ownership"
                            + " order by consumption_id,partition_id");
        for (var root : roots) f.worker.drain(root);
        assertTrue(
                roots.stream()
                        .noneMatch(
                                org.springframework.context.ConfigurableApplicationContext
                                        ::isActive));
        assertEquals(
                0,
                f.worker.read.queryForObject(
                        "select count(*) from consumption_slot", Integer.class));
        // Conservative restart: retain the real 180s lease and wait for its normal expiry.
        f.worker.select(V3ConsumptionWorkerSettings.READ_NAMES.subList(0, 3), true);
        f.start();
        f.worker.select(V3ConsumptionWorkerSettings.READ_NAMES.subList(3, 7), false);
        f.worker.start(Map.of("pocoma.v3.worker.worker-name", "i4-projection-restart"));
        ReadMonolithI8Fixture.await(
                () -> {
                    var now =
                            f.worker.read.queryForList(
                                    "select consumption_id,partition_id,owner_token from"
                                        + " partition_ownership order by"
                                        + " consumption_id,partition_id");
                    return now.size() == 28
                            && java.util.stream.IntStream.range(0, 28)
                                    .allMatch(
                                            i ->
                                                    !Objects.equals(
                                                            now.get(i).get("owner_token"),
                                                            tokens.get(i).get("owner_token")))
                            && now.stream().allMatch(r -> r.get("owner_token") != null)
                            && f.worker.read.queryForObject(
                                    "select count(*)=28 from partition_ownership where"
                                        + " owned_until>clock_timestamp()",
                                    Boolean.class);
                },
                Duration.ofSeconds(210));
        UUID second = create(Instant.parse("2030-01-01T00:00:00Z"));
        link(second);
        assertEquals(
                initialCount + 1,
                f.worker.read.queryForObject(
                        "select count(*) from read_current_invitation", Integer.class));
        f.http.evidence(
                "I4-topology",
                Map.of(
                        "readParticipants",
                        7,
                        "partitionsPerParticipant",
                        4,
                        "configuredPoolMaximum",
                        65,
                        "workerLimit",
                        4,
                        "leaseSeconds",
                        180,
                        "rootReadCounts",
                        List.of(3, 4),
                        "restart",
                        "natural lease expiry"));
    }

    Map<String, java.sql.Timestamp> ownership() {
        var result = new HashMap<String, java.sql.Timestamp>();
        for (var row :
                f.worker.read.queryForList(
                        "select consumption_id,partition_id,owned_until from partition_ownership"
                            + " where owner_token is not null"))
            result.put(
                    row.get("consumption_id") + ":" + row.get("partition_id"),
                    (java.sql.Timestamp) row.get("owned_until"));
        return result;
    }
}
