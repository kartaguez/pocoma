package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Test sources are explicitly technical; real Command/POT/COMPLETION use delivered writers. */
final class DiscoveryPostgresFixture implements AutoCloseable {
    final ConsumptionLifecyclePostgresFixture write, read, target;
    final ConsumableFamily family;
    final ConsumptionDefinition definition;
    final List<String> trace = new CopyOnWriteArrayList<>();
    DiscoveryEngine.Settings settings =
            new DiscoveryEngine.Settings(
                    128, 32, 32, 64, Duration.ofMillis(500), Duration.ofSeconds(2));
    final JdbcDiscoveryAdmissionStore admissions;
    final NonBurnableReferences events;
    final BurnableReferences requests;
    final WorkerCapacity capacity;
    final ConsumptionParticipant participant;
    private boolean capturedBeforeDiscovery;
    boolean measureLocks;

    static UUID u(long n) {
        return new UUID(0, n);
    }

    static final Map<ConsumableFamily, long[]> P0 =
            Map.of(
                    ConsumableFamily.COMMAND,
                    new long[] {3, 9, 12, 15, 19},
                    ConsumableFamily.IDENTITY_REQUEST,
                    new long[] {3, 10, 11, 14, 16},
                    ConsumableFamily.POT_EVENT,
                    new long[] {2, 3, 4, 11, 14},
                    ConsumableFamily.INVITATION_EVENT,
                    new long[] {3, 10, 13, 14, 20},
                    ConsumableFamily.IDENTITY_EVENT,
                    new long[] {6, 7, 8, 18, 22},
                    ConsumableFamily.COMMAND_COMPLETION_EVENT,
                    new long[] {2, 4, 15, 24, 26},
                    ConsumableFamily.IDENTITY_COMPLETION_EVENT,
                    new long[] {17, 21, 24, 25, 27});
    static final Map<ConsumableFamily, Long> P1 =
            Map.of(
                    ConsumableFamily.COMMAND,
                    1L,
                    ConsumableFamily.IDENTITY_REQUEST,
                    2L,
                    ConsumableFamily.POT_EVENT,
                    5L,
                    ConsumableFamily.INVITATION_EVENT,
                    12L,
                    ConsumableFamily.IDENTITY_EVENT,
                    12L,
                    ConsumableFamily.COMMAND_COMPLETION_EVENT,
                    5L,
                    ConsumableFamily.IDENTITY_COMPLETION_EVENT,
                    3L);

    DiscoveryPostgresFixture(PostgreSQLContainer pg, ConsumableFamily family, boolean real)
            throws Exception {
        this.family = family;
        write = new ConsumptionLifecyclePostgresFixture(pg, true);
        read = new ConsumptionLifecyclePostgresFixture(pg, false);
        boolean burnable =
                family == ConsumableFamily.COMMAND || family == ConsumableFamily.IDENTITY_REQUEST;
        target = burnable ? write : read;
        definition =
                new ConsumptionDefinition(
                        new ConsumptionId(
                                target.oracle.queryForObject(
                                        "select consumption_id from consumption_routing_contract"
                                                + " where family=? order by consumption_id limit 1",
                                        UUID.class,
                                        family.name())),
                        family,
                        burnable
                                ? ConsumptionDefinition.Authority.WRITE
                                : ConsumptionDefinition.Authority.READ);
        for (var f : List.of(write, read))
            f.script(
                    "grant-l2-discovery.sql",
                    pg.getUsername(),
                    Map.of("runtime_role", f.runtimeRole, "reactivation_role", f.adminRole));
        admissions = new JdbcDiscoveryAdmissionStore(target.source, settings);
        capacity = new WorkerCapacity(8, Map.of(definition.id(), 8));
        if (!burnable && !real) {
            write.oracle.execute(
                    "create table fixture_discovery_counter(position bigint not null);insert into"
                        + " fixture_discovery_counter values(0);create table"
                        + " fixture_discovery_event(position bigint primary key,id uuid unique not"
                        + " null,subject text not null)");
            write.oracle.execute(
                    "grant select on fixture_discovery_counter,fixture_discovery_event to "
                            + write.runtimeRole);
            events =
                    new NonBurnableReferences() {
                        public ConsumableFamily family() {
                            return family;
                        }

                        public Window window(long k, int max, RecoveryReadContext c) {
                            SpringAttemptTransactions.requireRead(write.source, c);
                            long safe =
                                    write.sql.queryForObject(
                                            "select position from fixture_discovery_counter",
                                            Long.class);
                            if (safe < k)
                                throw new IllegalStateException("fixture counter behind K");
                            long high = k + Math.min((long) max, safe - k);
                            var refs =
                                    write.sql.query(
                                            "select position,id,subject from"
                                                + " fixture_discovery_event where position>? and"
                                                + " position<=? order by position",
                                            (r, n) ->
                                                    new Reference(
                                                            r.getLong(1),
                                                            r.getObject(2, UUID.class),
                                                            r.getString(3)),
                                            k,
                                            high);
                            trace.add(
                                    "technical source family="
                                            + family
                                            + " K="
                                            + k
                                            + " H="
                                            + high
                                            + " refs="
                                            + refs
                                            + " pid/xid="
                                            + write.sql.queryForMap(
                                                    "select"
                                                        + " pg_backend_pid(),pg_current_xact_id()::text,clock_timestamp()"));
                            return new Window(k, high, refs);
                        }
                    };
        } else
            events =
                    burnable
                            ? null
                            : new JdbcEventDiscoveryReferences(write.source, family, settings);
        if (family == ConsumableFamily.IDENTITY_REQUEST) {
            write.oracle.execute(
                    "create table fixture_discovery_request(id uuid primary key,status text not"
                        + " null default 'PENDING' check(status in ('PENDING','TERMINAL')));create"
                        + " table fixture_discovery_result(id uuid primary key references"
                        + " fixture_discovery_request(id));create table"
                        + " fixture_discovery_completion(id uuid primary key references"
                        + " fixture_discovery_result(id))");
            write.oracle.execute(
                    "grant select,update(status) on fixture_discovery_request to "
                            + write.runtimeRole
                            + ";grant select,insert on"
                            + " fixture_discovery_result,fixture_discovery_completion to "
                            + write.runtimeRole);
            write.oracle.execute(
                    "create index fixture_pending_uuid on fixture_discovery_request(id) where"
                        + " status='PENDING';create function fixture_request_monotone() returns"
                        + " trigger language plpgsql as $$ begin if TG_OP='DELETE' or"
                        + " new.id<>old.id or old.status='TERMINAL' or new.status<>'TERMINAL' then"
                        + " raise exception 'immutable fixture request/monotone status' using"
                        + " errcode='23514';end if;return new;end $$;create trigger"
                        + " fixture_request_monotone before update or delete on"
                        + " fixture_discovery_request for each row execute function"
                        + " fixture_request_monotone();create function fixture_terminal_immutable()"
                        + " returns trigger language plpgsql as $$ begin raise exception 'immutable"
                        + " fixture terminal evidence' using errcode='23514';end $$;create trigger"
                        + " fixture_result_immutable before update or delete on"
                        + " fixture_discovery_result for each row execute function"
                        + " fixture_terminal_immutable();create trigger"
                        + " fixture_completion_immutable before update or delete on"
                        + " fixture_discovery_completion for each row execute function"
                        + " fixture_terminal_immutable();create function fixture_request_unit()"
                        + " returns trigger language plpgsql as $$ declare status text;has_result"
                        + " boolean;has_completion boolean;begin select r.status into status from"
                        + " fixture_discovery_request r where id=new.id;select exists(select 1 from"
                        + " fixture_discovery_result where id=new.id) into has_result;select"
                        + " exists(select 1 from fixture_discovery_completion where id=new.id) into"
                        + " has_completion;if (status='PENDING' and (has_result or has_completion))"
                        + " or (status='TERMINAL' and (not has_result or not has_completion or"
                        + " exists(select 1 from consumption_slot where consumption_id='"
                            + definition.id().value()
                            + "'::uuid and consumable_id=new.id))) then raise exception"
                            + " 'inconsistent fixture terminal unit' using errcode='23514';end"
                            + " if;return new;end $$;create constraint trigger fixture_request_unit"
                            + " after insert or update on fixture_discovery_request deferrable"
                            + " initially deferred for each row execute function"
                            + " fixture_request_unit();create constraint trigger"
                            + " fixture_result_unit after insert on fixture_discovery_result"
                            + " deferrable initially deferred for each row execute function"
                            + " fixture_request_unit();create constraint trigger"
                            + " fixture_completion_unit after insert on"
                            + " fixture_discovery_completion deferrable initially deferred for each"
                            + " row execute function fixture_request_unit();");
            requests = requestReferences(write.source);
            participant =
                    new ConsumptionParticipant() {
                        public Optional<Prepared> prepare(UUID id) {
                            return Optional.of(
                                    new Prepared() {
                                        public void finalizeIn(SlotCompletion c) {
                                            SpringAttemptTransactions.guarded(
                                                    write.source,
                                                    () -> {
                                                        write.sql.update(
                                                                "insert into"
                                                                    + " fixture_discovery_result"
                                                                    + " values(?)",
                                                                id);
                                                        write.sql.update(
                                                                "update fixture_discovery_request"
                                                                    + " set status='TERMINAL' where"
                                                                    + " id=? and status='PENDING'",
                                                                id);
                                                        return null;
                                                    });
                                            c.complete();
                                            SpringAttemptTransactions.guarded(
                                                    write.source,
                                                    () -> {
                                                        write.sql.update(
                                                                "insert into"
                                                                    + " fixture_discovery_completion"
                                                                    + " values(?)",
                                                                id);
                                                        return null;
                                                    });
                                            SpringAttemptTransactions.closePublication(
                                                    write.source);
                                        }

                                        public void requireCompletionReady() {
                                            assertEquals(
                                                    1,
                                                    write.sql.queryForObject(
                                                            "select count(*) from"
                                                                + " fixture_discovery_result where"
                                                                + " id=?",
                                                            Integer.class,
                                                            id));
                                        }
                                    });
                        }

                        public RecoveryObservation observe(UUID id, RecoveryReadContext c) {
                            SpringAttemptTransactions.requireRead(write.source, c);
                            return write.sql.queryForObject(
                                            "select exists(select 1 from fixture_discovery_result"
                                                    + " where id=?)",
                                            Boolean.class,
                                            id)
                                    ? RecoveryObservation.COMPLETED
                                    : RecoveryObservation.NOT_COMPLETED;
                        }
                    };
        } else {
            requests = burnable ? new JdbcCommandDiscoveryReferences(write.source, settings) : null;
            participant = target.participant;
        }
        trace.add(
                "PRECONDITIONS family="
                        + family
                        + " source="
                        + (real ? "real delivered" : "technical fixture")
                        + " effects="
                        + target.database
                        + "/"
                        + target.schema
                        + " settings="
                        + settings
                        + " literal p0="
                        + Arrays.toString(P0.get(family))
                        + " p1="
                        + P1.get(family));
    }

    boolean burnable() {
        return requests != null;
    }

    UUID q(int n) {
        return u(P0.get(family)[n]);
    }

    String requestTable() {
        return family == ConsumableFamily.COMMAND ? "v3_command" : "fixture_discovery_request";
    }

    String requestKey() {
        return family == ConsumableFamily.COMMAND ? "request_id" : "id";
    }

    void pending(UUID id) {
        if (family == ConsumableFamily.COMMAND)
            write.oracle.update(
                    "insert into"
                        + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                        + " values(?,'i3','actor','fixture',?,0,'CreatePot',?::jsonb)",
                    id,
                    UUID.randomUUID(),
                    PotCommandJson.encode(new PotCommand.CreatePot(new Label("I4 expected"))));
        else write.oracle.update("insert into fixture_discovery_request(id) values(?)", id);
        trace.add("admitted durable PENDING no slot " + id);
    }

    void fixtureEvents(List<UUID> ids) throws Exception {
        if (ids.isEmpty()) return;
        try (var c = write.admin.getConnection()) {
            c.setAutoCommit(false);
            long high;
            try (var st =
                    c.prepareStatement(
                            "update fixture_discovery_counter set position=position+? returning"
                                    + " position")) {
                st.setInt(1, ids.size());
                try (var rows = st.executeQuery()) {
                    rows.next();
                    high = rows.getLong(1);
                }
            }
            try (var st = c.prepareStatement("insert into fixture_discovery_event values(?,?,?)")) {
                long position = high - ids.size();
                for (UUID id : ids) {
                    st.setLong(1, ++position);
                    st.setObject(2, id);
                    st.setString(
                            3,
                            family == ConsumableFamily.IDENTITY_EVENT
                                    ? "EXTERNAL_USER_IDENTITY"
                                    : family.name());
                    st.addBatch();
                }
                st.executeBatch();
            }
            c.commit();
        }
    }

    PartitionLease lease(int p) {
        return target.technical(
                () ->
                        target.ownership
                                .acquire(definition, p, UUID.randomUUID(), Duration.ofSeconds(30))
                                .orElseThrow());
    }

    BurnableReferences requestReferences(javax.sql.DataSource source) {
        if (family == ConsumableFamily.COMMAND)
            return new JdbcCommandDiscoveryReferences(source, settings);
        final var sql = new org.springframework.jdbc.core.JdbcTemplate(source);
        return new BurnableReferences() {
            public ConsumableFamily family() {
                return family;
            }

            public Optional<UUID> upperPending(RecoveryReadContext c) {
                SpringAttemptTransactions.requireRead(source, c);
                return sql
                        .queryForList(
                                "select id from fixture_discovery_request where"
                                        + " status='PENDING' order by id desc limit 1",
                                UUID.class)
                        .stream()
                        .findFirst();
            }

            public List<UUID> pendingPage(
                    Optional<UUID> after, UUID upper, int size, RecoveryReadContext c) {
                SpringAttemptTransactions.requireRead(source, c);
                return after.isEmpty()
                        ? sql.queryForList(
                                "select id from fixture_discovery_request where"
                                        + " status='PENDING' and id<=? order by id limit ?",
                                UUID.class,
                                upper,
                                size)
                        : sql.queryForList(
                                "select id from fixture_discovery_request where"
                                        + " status='PENDING' and id>? and id<=? order by id"
                                        + " limit ?",
                                UUID.class,
                                after.orElseThrow(),
                                upper,
                                size);
            }

            public boolean tryProtectPending(UUID id) {
                return SpringAttemptTransactions.guarded(
                        source,
                        () ->
                                !sql.queryForList(
                                                "select id from"
                                                        + " fixture_discovery_request where"
                                                        + " id=? and status='PENDING' for"
                                                        + " update skip locked",
                                                id)
                                        .isEmpty());
            }
        };
    }

    DiscoveryEngine engine() {
        return engine(admissions, events, requests, target.tx);
    }

    DiscoveryEngine engine(
            DiscoveryAdmissionStore store,
            NonBurnableReferences eventSource,
            BurnableReferences requestSource,
            AttemptTransactions tx) {
        if (!capturedBeforeDiscovery) {
            try {
                snapshot("independent durable state immediately before first Discovery");
                capturedBeforeDiscovery = true;
            } catch (Exception failure) {
                throw new IllegalStateException("pre-stimulus snapshot failed", failure);
            }
        }
        if (burnable() && measureLocks) {
            var measurements =
                    new LockMeasurements(this, tx, target.source, target.ownership, requestSource);
            return new DiscoveryEngine(
                    definition,
                    measurements.transactions(),
                    measurements.ownership(),
                    store,
                    settings,
                    measurements.requests());
        }
        return burnable()
                ? new DiscoveryEngine(
                        definition, tx, target.ownership, store, settings, requestSource)
                : new DiscoveryEngine(
                        definition,
                        tx,
                        target.ownership,
                        store,
                        settings,
                        write.tx,
                        eventSource,
                        r ->
                                family != ConsumableFamily.IDENTITY_EVENT
                                        || r.subjectType().equals("EXTERNAL_USER_IDENTITY"));
    }

    /** Timing observations surround real operations; no fabricated locks or resolution. */
    static final class LockMeasurements {
        final DiscoveryPostgresFixture f;
        final AttemptTransactions owner;
        final org.springframework.jdbc.core.JdbcTemplate sql;
        final PartitionOwnershipStore locks;
        final BurnableReferences source;
        final ThreadLocal<Map<String, Instant[]>> held = new ThreadLocal<>();

        LockMeasurements(
                DiscoveryPostgresFixture f,
                AttemptTransactions owner,
                javax.sql.DataSource ds,
                PartitionOwnershipStore locks,
                BurnableReferences source) {
            this.f = f;
            this.owner = owner;
            this.sql = new org.springframework.jdbc.core.JdbcTemplate(ds);
            this.locks = locks;
            this.source = source;
        }

        Instant now() {
            return sql.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
        }

        AttemptTransactions transactions() {
            return new AttemptTransactions() {
                public <T> T read(Function<RecoveryReadContext, T> body) {
                    return owner.read(body);
                }

                public <T> T write(Supplier<T> body) {
                    var samples = new LinkedHashMap<String, Instant[]>();
                    held.set(samples);
                    var start = System.nanoTime();
                    final Instant[] end = {null};
                    try {
                        T result =
                                owner.write(
                                        () -> {
                                            f.trace.add(
                                                    "LOCK timing connection-and-begin-client-ns="
                                                            + (System.nanoTime() - start)
                                                            + " pid/xid/time="
                                                            + sql.queryForMap(
                                                                    "select"
                                                                        + " pg_backend_pid(),pg_current_xact_id()::text,clock_timestamp()"));
                                            T value = body.get();
                                            end[0] = now();
                                            return value;
                                        });
                        Instant resolved = f.target.now();
                        for (var sample : samples.entrySet()) {
                            long lower =
                                    Math.max(
                                            0,
                                            Duration.between(sample.getValue()[1], end[0])
                                                    .toNanos());
                            long upper =
                                    Math.max(
                                            lower,
                                            Duration.between(sample.getValue()[0], resolved)
                                                    .toNanos());
                            f.trace.add(
                                    "LOCK duration physical=COMMITTED key="
                                            + sample.getKey()
                                            + " acquired-between="
                                            + Arrays.toString(sample.getValue())
                                            + " release-between="
                                            + end[0]
                                            + ","
                                            + resolved
                                            + " lowerNs="
                                            + lower
                                            + " upperNs="
                                            + upper);
                        }
                        return result;
                    } finally {
                        held.remove();
                    }
                }

                public void poison(Throwable t) {
                    owner.poison(t);
                }

                public void requirePublicationClosed() {
                    owner.requirePublicationClosed();
                }
            };
        }

        PartitionOwnershipStore ownership() {
            return (PartitionOwnershipStore)
                    Proxy.newProxyInstance(
                            PartitionOwnershipStore.class.getClassLoader(),
                            new Class<?>[] {PartitionOwnershipStore.class},
                            (proxy, method, args) -> {
                                boolean protect = method.getName().equals("protectAcquisition");
                                Instant before = protect ? now() : null;
                                Object value;
                                try {
                                    value = method.invoke(locks, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getCause();
                                }
                                if (protect && value instanceof Optional<?> o && o.isPresent())
                                    held.get()
                                            .putIfAbsent(
                                                    "partition", new Instant[] {before, now()});
                                return value;
                            });
        }

        BurnableReferences requests() {
            return new BurnableReferences() {
                public ConsumableFamily family() {
                    return source.family();
                }

                public Optional<UUID> upperPending(RecoveryReadContext c) {
                    return source.upperPending(c);
                }

                public List<UUID> pendingPage(
                        Optional<UUID> a, UUID u, int n, RecoveryReadContext c) {
                    return source.pendingPage(a, u, n, c);
                }

                public boolean tryProtectPending(UUID id) {
                    Instant before = now();
                    boolean result = source.tryProtectPending(id);
                    Instant after = now();
                    f.trace.add(
                            "LOCK source try="
                                    + id
                                    + " protected="
                                    + result
                                    + " pg-before="
                                    + before
                                    + " pg-after="
                                    + after);
                    if (result)
                        held.get().putIfAbsent("source:" + id, new Instant[] {before, after});
                    return result;
                }
            };
        }
    }

    ConsumptionEngine lifecycle() {
        return new ConsumptionEngine(
                definition,
                target.tx,
                target.slots,
                participant,
                target.ownership,
                target.settings,
                capacity,
                c -> AttemptFailureClassifier.FailureKind.TECHNICAL);
    }

    void complete(PartitionLease lease, UUID id) {
        var e = lifecycle();
        var claims = e.acquire(lease, List.of(id)).claims();
        assertEquals(1, claims.size());
        assertEquals(ConsumptionEngine.Status.COMMITTED, e.execute(claims.getFirst()).status());
        assertFalse(slotIds().contains(id));
    }

    Set<UUID> slotIds() {
        return new HashSet<>(
                target.oracle.queryForList(
                        "select consumable_id from consumption_slot where consumption_id=?",
                        UUID.class,
                        definition.id().value()));
    }

    long k(int p) {
        var list =
                target.oracle.queryForList(
                        "select position from discovery_checkpoint where consumption_id=? and"
                                + " partition_id=?",
                        Long.class,
                        definition.id().value(),
                        p);
        return list.isEmpty() ? 0 : list.getFirst();
    }

    long count() {
        return target.oracle.queryForObject(
                "select count(*) from consumption_slot where consumption_id=? and partition_id=0"
                        + " and state<>'QUARANTINED'",
                Long.class,
                definition.id().value());
    }

    void slot(UUID id, boolean quarantine) {
        target.oracle.update(
                "insert into"
                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id,state)"
                    + " select ?,?,h,((h::bigint%4+4)%4)::int,? from(select"
                    + " consumption_hash_v1(?,?) h)x",
                definition.id().value(),
                id,
                quarantine ? "QUARANTINED" : "AVAILABLE",
                family.name(),
                id);
    }

    Connection lockSource(UUID id) throws Exception {
        var c = write.admin.getConnection();
        c.setAutoCommit(false);
        try (var s =
                c.prepareStatement(
                        "select "
                                + requestKey()
                                + " from "
                                + requestTable()
                                + " where "
                                + requestKey()
                                + "=? for update")) {
            s.setObject(1, id);
            s.executeQuery().close();
        }
        return c;
    }

    interface Interceptor {
        void run(String method, Object[] args) throws Exception;
    }

    static <T> T wrap(Class<T> type, T real, Interceptor before, Interceptor after) {
        return type.cast(
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, args) -> {
                            before.run(method.getName(), args);
                            try {
                                Object result = method.invoke(real, args);
                                after.run(method.getName(), args);
                                return result;
                            } catch (InvocationTargetException ex) {
                                throw ex.getCause();
                            }
                        }));
    }

    void snapshot(String name) throws Exception {
        for (var authority : target == write ? List.of(write) : List.of(target, write)) {
            try (var c = authority.admin.getConnection()) {
                c.setReadOnly(true);
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                c.setAutoCommit(false);
                trace.add(
                        name
                                + " authority="
                                + authority.database
                                + "/"
                                + authority.schema
                                + " "
                                + rows(
                                        c,
                                        "select"
                                            + " pg_backend_pid(),pg_current_xact_id()::text,clock_timestamp()"));
                var tables =
                        new ArrayList<>(
                                List.of(
                                        "consumption_slot",
                                        "discovery_checkpoint",
                                        "partition_ownership",
                                        "consumption_routing_contract"));
                if (authority == write)
                    tables.addAll(
                            List.of(
                                    "v3_command",
                                    "v3_command_result",
                                    "v3_pot_identity",
                                    "v3_pot_version",
                                    "v3_shareholder_identity",
                                    "v3_expense_identity",
                                    "v3_pot_header",
                                    "v3_pot_details",
                                    "v3_shareholder_header",
                                    "v3_shareholder_details",
                                    "v3_shareholder_weight",
                                    "v3_expense_header",
                                    "v3_expense_details",
                                    "v3_expense_financials",
                                    "v3_expense_participant",
                                    "v3_pot_event",
                                    "v3_pot_event_component",
                                    "v3_command_completion_event",
                                    "v3_event_counter",
                                    "v3_event_position",
                                    "fixture_discovery_counter",
                                    "fixture_discovery_event",
                                    "fixture_discovery_request",
                                    "fixture_discovery_result",
                                    "fixture_discovery_completion"));
                else
                    tables.addAll(
                            List.of(
                                    "fixture_effect",
                                    "fixture_effect_by_consumption",
                                    "fixture_discovery_units"));
                for (String table : tables) {
                    try (var statement = c.prepareStatement("select to_regclass(?)::text")) {
                        statement.setString(1, table);
                        try (var r = statement.executeQuery()) {
                            r.next();
                            if (r.getString(1) == null) continue;
                        }
                    }
                    trace.add(table + "=" + rows(c, "select * from " + table + " order by 1"));
                }
                c.rollback();
            }
        }
    }

    static List<Map<String, Object>> rows(Connection c, String sql) throws Exception {
        try (var s = c.createStatement();
                var r = s.executeQuery(sql)) {
            var result = new ArrayList<Map<String, Object>>();
            while (r.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
                    row.put(r.getMetaData().getColumnLabel(i), r.getObject(i));
                result.add(row);
            }
            return result;
        }
    }

    void locks(String label) {
        trace.add(
                label
                        + " locks="
                        + write.oracle.queryForList(
                                "select"
                                    + " pid,locktype,mode,granted,relation::regclass::text,transactionid"
                                    + " from pg_locks where pid<>pg_backend_pid() order by"
                                    + " pid,locktype,mode"));
        trace.add(
                label
                        + " activity="
                        + write.oracle.queryForList(
                                "select pid,state,wait_event_type,wait_event,pg_blocking_pids(pid)"
                                        + " blockers,clock_timestamp() from pg_stat_activity where"
                                        + " datname in (current_database(),'i3_read')"));
    }

    void save(String method, String variant) throws Exception {
        snapshot("independent final");
        var p = Path.of("target/v3-i4-evidence/" + method + "-" + family + "-" + variant + ".txt");
        Files.createDirectories(p.getParent());
        Files.write(p, trace);
    }

    public void close() {
        read.close();
        write.close();
    }
}
