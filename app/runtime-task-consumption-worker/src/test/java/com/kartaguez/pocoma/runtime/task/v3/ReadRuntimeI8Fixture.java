package com.kartaguez.pocoma.runtime.task.v3;
import com.kartaguez.pocoma.contract.consumption.invocation.WorkerView;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionLease;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.WorkerCapacity;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;
import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.v3.BindingToken;
import com.kartaguez.pocoma.engine.write.identity.v3.ExecuteIdentityRequest;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.PotCommandJson;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.supra.polling.consumption.v3.V3ConsumptionWorker;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.kartaguez.pocoma.runtime.consumption.v3.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.*;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public final class ReadRuntimeI8Fixture implements AutoCloseable {
    static final String IMAGE =
            "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73";

    static void initialize(PostgreSQLContainer pg) throws Exception {
        var sql =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        sql.execute("create database i5_read");
        for (String database : List.of(pg.getDatabaseName(), "i5_read")) {
            var ds =
                    new DriverManagerDataSource(
                            "jdbc:postgresql://"
                                    + pg.getHost()
                                    + ":"
                                    + pg.getMappedPort(5432)
                                    + "/"
                                    + database,
                            pg.getUsername(),
                            pg.getPassword());
            new JdbcTemplate(ds).execute("create extension pgcrypto with schema public");
        }
    }

    static void await(BooleanSupplier predicate) throws Exception {
        await(predicate, Duration.ofSeconds(15));
    }

    static void await(BooleanSupplier predicate, Duration timeout) throws Exception {
        long end = System.nanoTime() + timeout.toNanos();
        while (!predicate.getAsBoolean() && System.nanoTime() < end) Thread.sleep(50);
        assertTrue(predicate.getAsBoolean(), "runtime condition deadline");
    }

    static DataSourceTransactionManager manager(javax.sql.DataSource source) {
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        return manager;
    }

    final PostgreSQLContainer pg;
    final String schema = "i5_" + UUID.randomUUID().toString().replace("-", "");
    final String writeRole = schema + "_w",
            readRole = schema + "_r",
            reactRole = schema + "_a",
            injectorRole = schema + "_i";
    final String writeUrl, readUrl;
    final JdbcTemplate write, read;
    final Map<String, Object> properties = new LinkedHashMap<>();
    final List<String> trace = new CopyOnWriteArrayList<>();
    final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    ReadRuntimeI8Fixture(PostgreSQLContainer pg) throws Exception {
        this.pg = pg;
        writeUrl =
                "jdbc:postgresql://"
                        + pg.getHost()
                        + ":"
                        + pg.getMappedPort(5432)
                        + "/"
                        + pg.getDatabaseName()
                        + "?currentSchema="
                        + schema;
        readUrl =
                "jdbc:postgresql://"
                        + pg.getHost()
                        + ":"
                        + pg.getMappedPort(5432)
                        + "/i5_read?currentSchema="
                        + schema;
        write =
                new JdbcTemplate(
                        new DriverManagerDataSource(writeUrl, pg.getUsername(), pg.getPassword()));
        read =
                new JdbcTemplate(
                        new DriverManagerDataSource(readUrl, pg.getUsername(), pg.getPassword()));
        for (String role : List.of(writeRole, readRole, reactRole, injectorRole))
            write.execute("create role " + role + " login password 'i5-fixture'");
        for (boolean w : List.of(true, false)) {
            String url = w ? writeUrl : readUrl,
                    db = w ? pg.getDatabaseName() : "i5_read",
                    role = w ? writeRole : readRole;
            Flyway.configure()
                    .dataSource(url, pg.getUsername(), pg.getPassword())
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations(
                            w
                                    ? new String[] {
                                        "classpath:db/v3-pot/migration",
                                        "classpath:db/v3-pot-execution/migration",
                                        "classpath:db/v3-consumption-write/migration",
                                        "classpath:db/v3-identity/migration",
                                        "classpath:db/v3-admission/migration",
                                        "classpath:db/v3-invitation/migration"
                                    }
                                    : new String[] {
                                        "classpath:db/v3-consumption-read/migration",
                                        "classpath:db/v3-read-business/migration",
                                        "classpath:db/v3-read-invitation/migration"
                                    })
                    .placeholders(
                            Map.of(
                                    "expectedDatabase",
                                    db,
                                    "expectedSchema",
                                    schema,
                                    "expectedAdmin",
                                    pg.getUsername(),
                                    "expectedRuntime",
                                    role))
                    .load()
                    .migrate();
            if (w) script("configure-l3-identity-logging.sql", true);
            (w ? write : read).execute("revoke temporary on database " + db + " from public");
            for (String script :
                    List.of(
                            "bootstrap-l2-routing.sql",
                            "grant-l2-lifecycle.sql",
                            "grant-l2-discovery.sql")) script(script, w);
        }
        String prefix = "pocoma.v3.worker.";
        properties.put(prefix + "enabled", "true");
        properties.put(prefix + "environment", "DEV");
        properties.put(prefix + "fixture-identity", "false");
        properties.put(prefix + "identity-authority", "real");
        properties.put(prefix + "identity-request", "false");
        properties.put(prefix + "command-enabled", "false");
        properties.put(prefix + "technical-probes", "false");
        properties.put(prefix + "worker-name", "i5-test");
        properties.put(prefix + "worker-limit", "4");
        properties.put(prefix + "consumption-limits", "");
        properties.put(prefix + "partitions", "");
        properties.put(prefix + "read-participants", "");
        properties.put(prefix + "lifecycle.lease-duration-ms", "240000");
        properties.put(prefix + "read-limits.expense-batch", "50");
        properties.put(prefix + "read-limits.navigation-page", "20");
        properties.put(prefix + "read-limits.response-budget", "16000000");
        var identity =
                write.queryForMap("select inet_server_addr()::text addr,inet_server_port() port");
        String targetPrefix = pg.getHost() + "," + pg.getMappedPort(5432) + ",";
        properties.put(
                prefix + "dev-targets",
                targetPrefix
                        + pg.getDatabaseName()
                        + ","
                        + schema
                        + ","
                        + writeRole
                        + ","
                        + identity.get("addr")
                        + ","
                        + identity.get("port")
                        + ";"
                        + targetPrefix
                        + "i5_read,"
                        + schema
                        + ","
                        + readRole
                        + ","
                        + identity.get("addr")
                        + ","
                        + identity.get("port"));
        for (boolean w : List.of(true, false)) {
            String authority = w ? "write" : "read";
            properties.put(
                    "pocoma.v3.pilot." + authority + ".database",
                    w ? pg.getDatabaseName() : "i5_read");
            properties.put("pocoma.v3.pilot." + authority + ".schema", schema);
            properties.put("pocoma.v3.pilot." + authority + ".username", w ? writeRole : readRole);
            for (String pool : List.of("business", "control")) {
                String p = prefix + authority + "-" + pool + ".";
                properties.put(p + "url", w ? writeUrl : readUrl);
                properties.put(p + "username", w ? writeRole : readRole);
                properties.put(p + "password", "i5-fixture");
            }
        }
        trace.add(
                "initial expected: nine canonical business contracts, no technical probes; no"
                        + " slots/K/effects; fresh "
                        + schema);
    }

    void script(String name, boolean w) throws Exception {
        var destination = "/tmp/" + schema + name;
        pg.copyFileToContainer(
                MountableFile.forHostPath(Path.of("../../scripts/v3/" + name).toAbsolutePath()),
                destination);
        var result =
                pg.execInContainer(
                        "env",
                        "PGOPTIONS=-c search_path=" + schema,
                        "psql",
                        "-X",
                        "-U",
                        pg.getUsername(),
                        "-d",
                        w ? pg.getDatabaseName() : "i5_read",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-v",
                        "expected_database=" + (w ? pg.getDatabaseName() : "i5_read"),
                        "-v",
                        "expected_schema=" + schema,
                        "-v",
                        "expected_admin=" + pg.getUsername(),
                        "-v",
                        "authority=" + (w ? "WRITE" : "READ"),
                        "-v",
                        "runtime_role=" + (w ? writeRole : readRole),
                        "-v",
                        "reactivation_role=" + reactRole,
                        "-v",
                        "injector_role=" + injectorRole,
                        "-v",
                        "environment=DEV",
                        "-v",
                        "fixture_identity=false",
                        "-v",
                        "identity_authority=real",
                        "-v",
                        "identity_read_role=" + readRole,
                        "-v",
                        "technical_probes=false",
                        "-f",
                        destination);
        assertEquals(0, result.getExitCode(), result.getStderr());
        // Do not copy SQL stdout: it can contain fixture or fencing values.
        trace.add(
                "administrative "
                        + name
                        + " "
                        + (w ? "WRITE" : "READ")
                        + " rc="
                        + result.getExitCode());
    }

    Class<?> root = com.kartaguez.pocoma.PocomaTaskConsumptionWorkerApplication.class;

    ConfigurableApplicationContext start() {
        return start(Map.of());
    }

    ConfigurableApplicationContext start(Map<String, Object> overrides) {
        var all = new LinkedHashMap<>(properties);
        all.putAll(overrides);
        var app = new SpringApplication(root);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(all);
        var ctx = app.run("--spring.profiles.active=v3-read-worker");
        contexts.add(ctx);
        return ctx;
    }

    IdentityRequest admit(String operation, String actor, String target, BindingToken token) {
        UUID id = UUID.randomUUID();
        var e1 = new ExternalIdentity("controlled-runtime", actor);
        var e2 = new ExternalIdentity("controlled-runtime", target);
        var time = Instant.parse("2026-10-30T00:00:00Z");
        var req =
                new IdentityRequest(
                        id,
                        e1,
                        IdentityRequestJson.operation(operation, e2),
                        token,
                        time,
                        new IdentityRequest.AdmissionContext(
                                "runtime-test",
                                "L3/I3",
                                e1,
                                e2,
                                id.toString(),
                                id.toString(),
                                time,
                                Map.of("scope", "internal-test"),
                                IdentityRequest.Origin.CONTROLLED_INTERNAL));
        var source = new DriverManagerDataSource(writeUrl, pg.getUsername(), pg.getPassword());
        var tx = new SpringAttemptTransactions(source, manager(source), 5);
        var store = new JdbcIdentityRequestStore(source, tx);
        tx.write(() -> store.insertPending(req));
        return req;
    }

    void verify(IdentityRequest req) throws Exception {
        await(
                () ->
                        "TERMINAL"
                                .equals(
                                        write.queryForObject(
                                                "select status from v3_identity_request where"
                                                        + " request_id=?",
                                                String.class,
                                                req.requestId())),
                Duration.ofSeconds(90));
        var source = new DriverManagerDataSource(writeUrl, pg.getUsername(), pg.getPassword());
        var tx = new SpringAttemptTransactions(source, manager(source), 5);
        var observation = new JdbcIdentityRequestObservation(source);
        assertEquals(
                RecoveryObservation.COMPLETED,
                tx.read(c -> observation.observe(req.requestId(), c)));
        assertEquals(
                0,
                write.queryForObject(
                        "select count(*) from consumption_slot where consumable_id=?",
                        Integer.class,
                        req.requestId()));
        trace.add(
                "real Identity request="
                        + req.requestId()
                        + " operation="
                        + IdentityRequestJson.operation(req)
                        + " terminal and positioned; slot absent");
    }

    void enableCommands() {
        properties.put("pocoma.v3.worker.command-enabled", "true");
        properties.put("pocoma.v3.worker.consumption-limits", "IdentityRequest=4,Command=4");
        properties.put("pocoma.v3.worker.partitions", "IdentityRequest=0|1|2|3;Command=0|1|2|3");
    }

    PotCommand admitCommand(
            String subject, String token, PotId pot, long observed, PotCommand.Intent intent) {
        var command =
                new PotCommand(
                        UUID.randomUUID(),
                        new PotCommand.Eui("controlled-runtime", subject),
                        token,
                        pot,
                        observed,
                        intent);
        // Controlled internal fixture admission; the Identity-only injector has no Command
        // grant.
        write.update(
                "insert into"
                    + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                    + " values (?,?,?,?,?,?,?,?::jsonb)",
                command.requestId(),
                command.requester().issuer(),
                command.requester().subject(),
                command.bindingToken(),
                pot.value(),
                observed,
                intent.getClass().getSimpleName(),
                PotCommandJson.encode(intent));
        return command;
    }

    void verifyCommand(PotCommand command, String outcome, String rejection) throws Exception {
        await(
                () ->
                        "TERMINAL"
                                .equals(
                                        write.queryForObject(
                                                "select status from v3_command where"
                                                        + " request_id=?",
                                                String.class,
                                                command.requestId())),
                Duration.ofSeconds(90));
        var result =
                write.queryForMap(
                        "select outcome,rejection from v3_command_result where request_id=?",
                        command.requestId());
        assertEquals(outcome, result.get("outcome"));
        assertEquals(rejection, result.get("rejection"));
        assertEquals(
                1,
                write.queryForObject(
                        "select count(*) from v3_command_completion_event " + "where subject_id=?",
                        Integer.class,
                        command.requestId()));
        assertEquals(
                0,
                write.queryForObject(
                        "select count(*) from consumption_slot where consumable_id=?",
                        Integer.class,
                        command.requestId()));
        trace.add(
                "I4 Command request="
                        + command.requestId()
                        + " outcome="
                        + outcome
                        + " rejection="
                        + rejection);
    }

    UUID user(String subject) {
        return write.queryForObject(
                "select user_id from v3_eui_authority a join v3_eui_version v "
                        + "on a.eui_id=v.eui_id and a.current_version=v.version where "
                        + "a.issuer='controlled-runtime' and a.subject=?",
                UUID.class,
                subject);
    }

    BindingToken ownToken(String subject) {
        return new BindingToken(
                write.queryForObject(
                        "select binding_token from v3_eui_authority a join v3_eui_version v on"
                                + " a.eui_id=v.eui_id and a.current_version=v.version where"
                                + " a.issuer='controlled-runtime' and a.subject=?",
                        String.class,
                        subject));
    }

    String snapshot() {
        var result = new ArrayList<String>();
        for (String table :
                List.of(
                        "v3_pocoma_user",
                        "v3_eui_authority",
                        "v3_eui_version",
                        "v3_identity_request_result",
                        "v3_identity_event",
                        "v3_identity_completion_event",
                        "v3_event_counter",
                        "v3_event_position"))
            result.add(
                    write.queryForObject(
                            "select coalesce(md5(string_agg(row_to_json(t)::text,',' order by"
                                    + " row_to_json(t)::text)),'empty') from "
                                    + table
                                    + " t",
                            String.class));
        return result.toString();
    }

    V3ConsumptionWorker worker(ConfigurableApplicationContext ctx) {
        return ctx.getBean(V3ConsumptionWorker.class);
    }

    void drain(ConfigurableApplicationContext ctx) throws Exception {
        var callback = new AtomicInteger();
        worker(ctx).requestDrain(callback::incrementAndGet);
        await(
                () ->
                        worker(ctx).snapshot().state() == WorkerView.State.STOPPED
                                && callback.get() == 1);
        assertEquals(1, callback.get());
        ctx.close();
        contexts.remove(ctx);
    }

    void evidence(String name) throws Exception {
        for (var sql : List.of(write, read)) {
            trace.add(
                    "physical "
                            + sql.queryForMap(
                                    "select"
                                        + " current_database(),current_schema(),clock_timestamp(),pg_backend_pid(),pg_current_xact_id()::text"));
            trace.add(
                    "counts "
                            + sql.queryForMap(
                                    "select (select count(*) from consumption_slot)"
                                            + " slots,(select count(*) from discovery_checkpoint)"
                                            + " checkpoints,(select count(*) from"
                                            + " partition_ownership where owner_token is not null)"
                                            + " owned"));
        }
        var file = Path.of("target/l4-i8-evidence/" + name + ".txt");
        Files.createDirectories(file.getParent());
        Files.write(file, trace);
    }

    private final Map<String, PartitionLease> producerLeases = new HashMap<>();

    void produce(IdentityRequest request) throws Exception {
        var source = new DriverManagerDataSource(writeUrl, writeRole, "i5-fixture");
        var owner = new SpringAttemptTransactions(source, manager(source), 60);
        var participant =
                new ExecuteIdentityRequest(
                        new JdbcIdentityRequestStore(source, owner),
                        new JdbcIdentityRequestObservation(source),
                        new JdbcIdentityAuthority(source, owner),
                        () -> new BindingToken("i8-" + UUID.randomUUID()),
                        UUID::randomUUID);
        executeProducer("IdentityRequest", request.requestId(), participant, source, owner);
        verify(request);
    }

    void produce(PotCommand request) throws Exception {
        var source = new DriverManagerDataSource(writeUrl, writeRole, "i5-fixture");
        var owner = new SpringAttemptTransactions(source, manager(source), 60);
        var participant =
                new ExecutePotCommand(
                        new JdbcPotCommandStore(source, owner),
                        new JdbcPotCommandObservation(source),
                        new JdbcIdentityPotActorAuthority(source, owner),
                        new JdbcPotHistoryAdapter(source),
                        new JdbcPotRevisionAdapter(source),
                        Clock.systemUTC());
        executeProducer("Command", request.requestId(), participant, source, owner);
        verifyCommand(request, "CHANGED", null);
    }

    private void executeProducer(
            String name,
            UUID id,
            ConsumptionParticipant participant,
            javax.sql.DataSource source,
            SpringAttemptTransactions owner) {
        var definition = V3ConsumptionWorkerSettings.definition(name);
        write.update(
                "insert into"
                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                    + " select ?,?,h,((h::bigint%4+4)%4)::int from(select consumption_hash_v1(?,?)"
                    + " h)x",
                definition.id().value(), id, definition.family().name(), id);
        var engine =
                new ConsumptionEngine(
                        definition,
                        owner,
                        new JdbcConsumptionSlotStore(source),
                        participant,
                        new JdbcPartitionOwnershipStore(source),
                        LifecycleSettings.pilot(),
                        new WorkerCapacity(1, Map.of(definition.id(), 1)),
                        failure -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
        int partition = RoutingV1.partition(RoutingV1.hash(definition.family(), id), 4);
        var lease = producerLeases.get(name + partition);
        if (lease != null)
            lease = engine.renewPartitions(List.of(lease)).stream().findFirst().orElse(null);
        if (lease == null) lease = engine.acquirePartition(partition).orElseThrow();
        producerLeases.put(name + partition, lease);
        var result = engine.execute(engine.acquire(lease, List.of(id)).claims().getFirst());
        assertEquals(
                ConsumptionProtocol.Status.COMMITTED,
                result.status(),
                () -> name + " producer: " + result.cause());
    }

    void witnesses() {
        read.execute(
                "create table i8_witness(t text,pid integer,xid text,consumption_id"
                    + " uuid,consumable_id uuid,at timestamptz default clock_timestamp())");
        read.execute("grant select,insert on i8_witness to " + readRole);
        read.execute(
                "create function i8_witness() returns trigger language plpgsql as $$ begin if"
                    + " tg_table_name='consumption_slot' then insert into"
                    + " i8_witness(t,pid,xid,consumption_id,consumable_id)"
                    + " values(tg_table_name,pg_backend_pid(),pg_current_xact_id()::text,old.consumption_id,old.consumable_id);"
                    + " else insert into i8_witness(t,pid,xid)"
                    + " values(tg_table_name,pg_backend_pid(),pg_current_xact_id()::text); end"
                    + " if;return null;end $$");
        for (var table :
                List.of(
                        "read_subobject_version",
                        "read_pot_version",
                        "read_current_pot",
                        "read_pot_balance",
                        "read_pot_visibility_state",
                        "read_user_pot",
                        "read_current_binding",
                        "read_command_result",
                        "read_identity_request_result"))
            read.execute(
                    "create trigger i8_witness after insert or update on "
                            + table
                            + " for each row execute function i8_witness()");
        read.execute(
                "create trigger i8_witness after delete on consumption_slot for each row execute"
                    + " function i8_witness()");
    }

    void physical(String scenario) {
        trace.add(
                scenario
                        + " SQL="
                        + read.queryForMap(
                                "select"
                                    + " clock_timestamp(),pg_backend_pid(),pg_current_xact_id()::text"));
        trace.add(
                scenario
                        + " leases="
                        + read.queryForList(
                                "select consumption_id,partition_id,owner_token is not null"
                                    + " owned,owned_until,clock_timestamp() observed_at from"
                                    + " partition_ownership order by consumption_id,partition_id"));
        trace.add(
                scenario
                        + " K="
                        + read.queryForList(
                                "select * from discovery_checkpoint order by"
                                    + " consumption_id,partition_id"));
        trace.add(
                scenario
                        + " slots="
                        + read.queryForList(
                                "select consumption_id,consumable_id,partition_id,claim_token is"
                                    + " not null claimed,claimed_until,attempt_count,retry_after"
                                    + " from consumption_slot order by"
                                    + " consumption_id,consumable_id"));
    }

    void select(List<String> names, boolean writeParticipants) {
        String prefix = "pocoma.v3.worker.";
        properties.put(prefix + "read-participants", String.join(",", names));
        properties.put(prefix + "identity-request", Boolean.toString(writeParticipants));
        properties.put(prefix + "command-enabled", Boolean.toString(writeParticipants));
        var all = new ArrayList<>(names);
        if (writeParticipants) all.addAll(List.of("Command", "IdentityRequest"));
        properties.put(
                prefix + "consumption-limits",
                all.stream().map(n -> n + "=1").collect(java.util.stream.Collectors.joining(",")));
        properties.put(
                prefix + "partitions",
                all.stream()
                        .map(n -> n + "=0|1|2|3")
                        .collect(java.util.stream.Collectors.joining(";")));
    }

    V3ReadFacadeComposition.Services services(ConfigurableApplicationContext context) {
        return context.getBean(V3ReadFacadeComposition.Services.class);
    }

    ExternalIdentity eui(String subject) {
        return new ExternalIdentity("controlled-runtime", subject);
    }

    String coordination() {
        var values = new ArrayList<String>();
        for (var sql : List.of(write, read))
            for (var table :
                    List.of("partition_ownership", "discovery_checkpoint", "consumption_slot"))
                values.add(
                        sql.queryForObject(
                                "select coalesce(md5(string_agg(row_to_json(t)::text,',' order by"
                                        + " row_to_json(t)::text)),'empty') from "
                                        + table
                                        + " t",
                                String.class));
        return values.toString();
    }

    public void close() throws Exception {
        for (var ctx : List.copyOf(contexts)) drain(ctx);
    }
}
