package com.kartaguez.pocoma.runtime.task.v3;

import static org.junit.jupiter.api.Assertions.*;
import static com.kartaguez.pocoma.runtime.task.v3.V3QuotaHttpFixture.*;
import static com.kartaguez.pocoma.engine.read.pot.v3.ReadPotV3Result.*;
import com.kartaguez.pocoma.supra.http.read.v3.*;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.io.*;
import java.lang.management.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

@Testcontainers
class V3ReadQuotaPostgresTest {
    @Container static final PostgreSQLContainer PG = container();
    @SpringBootConfiguration @EnableAutoConfiguration @Import(V3ReadHttpConfiguration.class) static class HttpRoot {}
    V3QuotaHttpFixture f;
    @BeforeEach void fixture() throws Exception { f = new V3QuotaHttpFixture(PG); f.start(HttpRoot.class); f.seed(POT, 1, 50, Set.of(OWNER), false, true, true, "quota"); }
    @AfterEach void close() throws Exception { try { if (f != null) f.evidence("Quota-" + f.schema, Map.of("sql", f.sqlStats(), "activeBackends", f.activeBackends(), "poolActive", f.pool().getHikariPoolMXBean().getActiveConnections())); } finally { if (f != null) f.close(); } }
    static void await(BooleanSupplier predicate, Duration duration) throws Exception {
        long end = System.nanoTime() + duration.toNanos(); while (!predicate.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5); assertTrue(predicate.getAsBoolean(), "physical condition deadline");
    }
    void waiters(int count) throws Exception { await(() -> f.admin.queryForObject("select count(*) from pg_stat_activity where usename=? and wait_event_type='Lock'", Integer.class, f.role) == count, Duration.ofSeconds(1)); }
    final class Held implements AutoCloseable {
        final Connection gate; final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor(); final List<Future<?>> readers = new ArrayList<>();
        Held() throws Exception {
            gate = f.admin.getDataSource().getConnection(); gate.setAutoCommit(false);
            gate.createStatement().execute("lock table read_current_binding,read_command_result,read_identity_request_result in access exclusive mode");
            readers.add(executor.submit(() -> f.services().current(f.eui("owner"), TOKEN, POT, 0)));
            readers.add(executor.submit(() -> f.services().binding(f.eui("owner"), 0))); waiters(2);
        }
        public void close() throws Exception {
            gate.commit(); gate.close(); for (var reader : readers) assertInstanceOf(Available.class, reader.get(5, TimeUnit.SECONDS)); executor.close();
        }
    }
    @Test void invalidInputBeforeQuota() throws Exception {
        try (var held = new Held()) {
            long calls = businessCalls();
            for (List<ExpenseRef> refs : Arrays.asList(null, List.<ExpenseRef>of(), Arrays.asList((ExpenseRef) null), Collections.nCopies(2, new ExpenseRef(id(1000), 1, 1, 1, false)), Collections.nCopies(51, new ExpenseRef(id(1000), 1, 1, 1, false))))
                assertInstanceOf(InvalidInput.class, f.services().expenses(f.eui("owner"), TOKEN, POT, 1, refs));
            for (int limit : List.of(0, 101)) { assertInstanceOf(InvalidInput.class, f.services().visible(f.eui("owner"), TOKEN, null, limit)); assertEquals(400, f.get("/me/pots?limit=" + limit).statusCode()); }
            for (String body : List.of(f.batch(0, 1), f.batch(51, 1), "{\"references\":null}", "{\"references\":[" + ref(1000, 1) + "," + ref(1000, 1) + "]}")) assertEquals(400, f.post(f.path(POT, 1) + "/expenses/query", body).statusCode());
            assertEquals(calls, businessCalls()); assertEquals(2, f.pool().getHikariPoolMXBean().getActiveConnections());
        }
        try (var gate = f.admin.getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            gate.setAutoCommit(false); gate.createStatement().execute("lock table read_current_binding in access exclusive mode");
            var refs = new ArrayList<>(List.of(new ExpenseRef(id(1000), 1, 1, 1, false)));
            var response = executor.submit(() -> f.services().expenses(f.eui("owner"), TOKEN, POT, 1, refs)); waiters(1); refs.clear(); refs.add(new ExpenseRef(id(9999), 1, 1, 1, false)); gate.commit();
            var result = response.get(5, TimeUnit.SECONDS); assertInstanceOf(Available.class, result); assertEquals(id(1000), ((Available<List<Expense>>) result).value().getFirst().reference().id());
        }
    }
    @Test void twoReadersAndImmediateRefusal() throws Exception {
        try (var held = new Held()) {
            long started = System.nanoTime(); long calls = businessCalls();
            assertInstanceOf(Unavailable.class, f.services().commandResult(id(401), f.eui("owner")));
            assertInstanceOf(Unavailable.class, f.services().identityResult(id(403), f.eui("owner")));
            assertInstanceOf(Unavailable.class, f.services().binding(f.eui("other"), 0));
            assertTrue(System.nanoTime() - started < Duration.ofSeconds(1).toNanos(), "no wait for quota"); assertEquals(calls, businessCalls());
        }
        assertInstanceOf(Available.class, f.services().binding(f.eui("owner"), 0));
    }
    @Test void permitsAndConnectionsReleased() throws Exception {
        for (int n = 0; n < 10; n++) {
            assertEquals(200, f.get("/pots/" + POT).statusCode()); assertEquals(404, f.get("/pots/" + id(9999)).statusCode()); assertEquals(503, f.get("/pots/" + POT + "?minVersion=2").statusCode());
            assertEquals(0, f.pool().getHikariPoolMXBean().getActiveConnections()); assertEquals(0, f.activeBackends());
        }
        f.admin.execute("alter table read_current_binding rename to i1_binding_unavailable"); assertEquals(503, f.get("/me/binding").statusCode()); f.admin.execute("alter table i1_binding_unavailable rename to read_current_binding");
        assertEquals(0, f.pool().getHikariPoolMXBean().getActiveConnections());
        try (var held = new Held()) { assertEquals(2, f.pool().getHikariPoolMXBean().getActiveConnections()); }
    }
    @Test void sqlTimeoutPhysicalResolution() throws Exception {
        long start = System.nanoTime();
        try (var gate = f.admin.getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            gate.setAutoCommit(false); gate.createStatement().execute("lock table read_current_binding in access exclusive mode");
            var response = executor.submit(() -> f.services().current(f.eui("owner"), TOKEN, POT, 0)); waiters(1);
            assertInstanceOf(Unavailable.class, response.get(5, TimeUnit.SECONDS)); assertEquals(0, f.pool().getHikariPoolMXBean().getActiveConnections()); assertEquals(0, f.activeBackends()); gate.rollback();
        }
        f.evidence("Q04-timeout", Map.of("seconds", (System.nanoTime() - start) / 1e9, "physicalBackendsAfter", f.activeBackends(), "poolActiveAfter", f.pool().getHikariPoolMXBean().getActiveConnections()));
        assertInstanceOf(Available.class, f.services().binding(f.eui("owner"), 0));
    }
    Socket socket(String path) throws Exception {
        var uri = URI.create(f.baseUrl); var socket = new Socket(); socket.setReceiveBufferSize(1024); socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 5000); socket.setSoTimeout(6000);
        String request = "GET /api/v3" + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + f.jwt("owner") + "\r\nX-Pocoma-Binding-Token: " + TOKEN + "\r\nConnection: close\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush(); return socket;
    }
    @Test void cancellationDuringJdbc() throws Exception {
        // A disconnected caller is not evidence of JDBC cancellation. Retain until timeout/rollback resolves.
        try (var gate = f.admin.getDataSource().getConnection()) {
            gate.setAutoCommit(false); gate.createStatement().execute("lock table read_current_binding in access exclusive mode");
            Socket first = socket("/pots/" + POT), second = socket("/pots/" + POT); waiters(2); first.close(); second.close();
            assertInstanceOf(Unavailable.class, f.services().binding(f.eui("owner"), 0)); assertEquals(2, f.pool().getHikariPoolMXBean().getActiveConnections());
            await(() -> f.pool().getHikariPoolMXBean().getActiveConnections() == 0 && f.context.getBean(V3ReadTransport.class).availableCapacity() == 2, Duration.ofSeconds(6));
            assertEquals(0, f.activeBackends()); gate.rollback();
        }
        f.evidence("Q05-cancellation", Map.of("strategy", "retain until PostgreSQL statement timeout and physical rollback", "releasedBeforePhysicalResolution", false, "activeBackendsAfter", f.activeBackends()));
    }
    static volatile Object responseDto;
    @Configuration(proxyBeanMethods = false) static class Probe {
        @Bean ServletRegistrationBean<HttpServlet> transportProbe(V3ReadTransport transport) {
            return new ServletRegistrationBean<>(new HttpServlet() { @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException { transport.write(response, responseDto); } }, "/api/v3/transport-probe");
        }
    }
    void probeServer() { f.context.close(); f.context = null; f.start(HttpRoot.class, Probe.class); }
    V3ReadDtos.Pot largeDto(int chars) { return new V3ReadDtos.Pot(1, POT, "é".repeat(chars), 1, 1, List.of(), Map.of(), List.of()); }
    @Test void disconnectDuringEncodingAndSend() throws Exception {
        probeServer(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        responseDto = new V3ReadDtos.Pot(1, POT, "encoding", 1, 1, List.of(), Map.of(), new AbstractList<ExpenseRef>() {
            public int size() { return 1; } public ExpenseRef get(int n) { entered.countDown(); try { if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout"); } catch (InterruptedException e) { throw new IllegalStateException(e); } return new ExpenseRef(id(1000), 1, 1, 1, false); }
        });
        Socket encoding = socket("/transport-probe"); assertTrue(entered.await(1, TimeUnit.SECONDS)); encoding.close(); assertEquals(1, f.context.getBean(V3ReadTransport.class).availableCapacity()); release.countDown();
        await(() -> f.context.getBean(V3ReadTransport.class).availableCapacity() == 2, Duration.ofSeconds(6));
        responseDto = largeDto(2_000_000); Socket sending = socket("/transport-probe"); assertEquals("HTTP/1.1 200", readStatus(sending)); sending.close();
        await(() -> f.context.getBean(V3ReadTransport.class).availableCapacity() == 2, Duration.ofSeconds(6)); assertEquals(0, f.pool().getHikariPoolMXBean().getActiveConnections());
    }
    static String readStatus(Socket socket) throws IOException {
        var line = new ByteArrayOutputStream(); int value; while ((value = socket.getInputStream().read()) != -1 && value != '\n') line.write(value); return line.toString(StandardCharsets.US_ASCII).trim().substring(0, 12);
    }
    @Test void httpInFlightUntilSendEnds() throws Exception {
        probeServer(); responseDto = largeDto(2_000_000);
        try (Socket first = socket("/transport-probe"); Socket second = socket("/transport-probe")) {
            assertEquals("HTTP/1.1 200", readStatus(first)); assertEquals("HTTP/1.1 200", readStatus(second));
            assertEquals(0, f.context.getBean(V3ReadTransport.class).availableCapacity()); long calls = businessCalls(); var refused = f.get("/me/binding");
            assertEquals(503, refused.statusCode()); assertTrue(refused.body().length <= 1024); assertEquals(calls, businessCalls()); assertEquals(0, f.pool().getHikariPoolMXBean().getActiveConnections());
        }
        await(() -> f.context.getBean(V3ReadTransport.class).availableCapacity() == 2, Duration.ofSeconds(6)); assertEquals(200, f.get("/me/binding").statusCode());
    }
    @Test void memoryAndCopies() throws Exception {
        assertTrue(Runtime.getRuntime().maxMemory() <= 512L * 1024 * 1024, "isolated qualification JVM must use -Xmx512m");
        var memory = ManagementFactory.getMemoryMXBean(); var peak = new AtomicLong(); var samples = new AtomicLong(); var sampling = new AtomicBoolean(true);
        long before = memory.getHeapMemoryUsage().getUsed();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var sampler = executor.submit(() -> { while (sampling.get()) { peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max); samples.incrementAndGet(); try { Thread.sleep(5); } catch (InterruptedException stop) { return; } } });
            f.seed(id(777), 1, 2000, 100, Set.of(OWNER), false, true, true, "renforcé-\\\"-" + "é".repeat(100));
            assertEquals(200, f.get(f.path(id(777), 1)).statusCode());
            assertTrue(memory.getHeapMemoryUsage().getMax() - memory.getHeapMemoryUsage().getUsed() > 32L * 1024 * 1024, "headroom before two finite calls");
            var one = executor.submit(() -> f.get(f.path(id(777), 1))); var two = executor.submit(() -> f.get(f.path(id(777), 1)));
            assertEquals(200, one.get(10, TimeUnit.SECONDS).statusCode()); assertEquals(200, two.get(10, TimeUnit.SECONDS).statusCode());
            f.seed(id(778), 1, 0, Set.of(OWNER), false, true, true, "x".repeat(4_194_304)); assertEquals(503, f.get("/pots/" + id(778)).statusCode());
            sampling.set(false); sampler.get(1, TimeUnit.SECONDS);
        } finally { sampling.set(false); }
        assertTrue(samples.get() > 0); assertTrue(peak.get() < Runtime.getRuntime().maxMemory());
        var os = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start(); String rss = new String(os.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim(); assertEquals(0, os.waitFor());
        var gc = ManagementFactory.getGarbageCollectorMXBeans().stream().map(g -> Map.of("name", g.getName(), "collections", g.getCollectionCount(), "milliseconds", g.getCollectionTime())).toList();
        f.evidence("Q08-memory", Map.of("heapMax", Runtime.getRuntime().maxMemory(), "heapBefore", before, "heapAfter", memory.getHeapMemoryUsage().getUsed(), "sampledPeak", peak.get(), "samples", samples.get(), "sampleResolutionMillis", 5, "rssKiB", rss, "gc", gc, "copies", "detached JDBC view + explicit DTO + bounded 16KiB segments; no complete encoded-array copy; server/socket copies not fully quantified"));
    }
    long businessCalls() { return f.sqlStats().stream().filter(s -> s.get("query").toString().toLowerCase(Locale.ROOT).startsWith("select") && s.get("query").toString().contains("read_")).mapToLong(s -> ((Number) s.get("calls")).longValue()).sum(); }
    @Test void jdbcCountsAndCosts() throws Exception {
        long before = businessCalls(); var batch = f.post(f.path(POT, 1) + "/expenses/query", f.batch(50, 1)); assertEquals(200, batch.statusCode()); assertEquals(3, businessCalls() - before, "binding + exact Pot + grouped subobjects, no N+1");
        f.seed(id(779), 1, 2000, 100, Set.of(OWNER), false, true, true, "grand");
        before = businessCalls(); assertEquals(200, f.get("/pots/" + id(779)).statusCode()); assertEquals(6, businessCalls() - before);
        before = businessCalls(); assertEquals(200, f.get(f.path(id(779), 1)).statusCode()); assertEquals(5, businessCalls() - before);
        f.evidence("Q09-jdbc", Map.of("batchSelects", 3, "currentGrandSelects", 6, "historyGrandSelects", 5, "executionsRowsTimes", f.sqlStats(), "batchHttpBytes", batch.body().length, "wireJdbcBytes", "NOT_MEASURED"));
    }
    @Test void workersProgressWithoutControlBorrow() throws Exception {
        // Existing I8 worker and physical authorities, no public admission or lease change.
        ReadRuntimeI8Fixture.initialize(PG);
        try (var workerFixture = new ReadRuntimeI8Fixture(PG)) {
            workerFixture.select(List.of("CurrentBinding"), false); var request = workerFixture.admit("REGISTRATION", "i1-worker", "i1-worker", null); workerFixture.produce(request);
            var context = workerFixture.start();
            try (var held = new Held()) {
                await(() -> workerFixture.read.queryForObject("select count(*) from read_current_binding where subject='i1-worker'", Integer.class) == 1, Duration.ofSeconds(1));
                assertEquals(2, f.pool().getHikariPoolMXBean().getActiveConnections());
                assertEquals(1, f.context.getBeansOfType(com.zaxxer.hikari.HikariDataSource.class).size(), "HTTP has only READ; no WRITE or Control source");
            }
            workerFixture.drain(context); workerFixture.evidence("L5-I1-Q10");
        }
    }
}
