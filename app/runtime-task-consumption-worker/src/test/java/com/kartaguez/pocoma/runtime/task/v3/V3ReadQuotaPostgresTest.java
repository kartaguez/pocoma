package com.kartaguez.pocoma.runtime.task.v3;

import static org.junit.jupiter.api.Assertions.*;
import static com.kartaguez.pocoma.runtime.task.v3.V3QuotaHttpFixture.*;
import static com.kartaguez.pocoma.contract.readquery.ReadPotV3Result.*;
import com.kartaguez.pocoma.supra.web.read.v3.*;
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
    @AfterEach void close() throws Exception { try { if (f != null && f.context != null) f.evidence("Quota-" + f.schema, Map.of("sql", f.sqlStats(), "activeBackends", f.activeBackends(), "poolActive", f.pool().getHikariPoolMXBean().getActiveConnections())); } finally { if (f != null) f.close(); } }
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
            var changingDuringCapture = new ArrayList<ExpenseRef>(List.of(new ExpenseRef(id(1000), 1, 1, 1, false))) {
                @Override public Object[] toArray() {
                    clear(); for (int n=0; n<51; n++) add(new ExpenseRef(id(1000+n),1,1,1,false)); return super.toArray();
                }
            };
            assertInstanceOf(InvalidInput.class, f.services().expenses(f.eui("owner"), TOKEN, POT, 1, changingDuringCapture), "the captured snapshot itself is validated before quota");
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
            // Keep both peers open and unread: server write timeout must physically abort them.
            long waiting = System.nanoTime();
            await(() -> f.context.getBean(V3ReadTransport.class).availableCapacity() == 2, Duration.ofSeconds(6));
            f.evidence("Q07-send-timeout", Map.of("secondsAfterRefusal", (System.nanoTime()-waiting)/1e9, "peersStillOpen", true, "capacityRecovered", 2));
        }
        await(() -> f.context.getBean(V3ReadTransport.class).availableCapacity() == 2, Duration.ofSeconds(6)); assertEquals(200, f.get("/me/binding").statusCode());
    }
    @Test void memoryAndCopies() throws Exception {
        if (Runtime.getRuntime().maxMemory() > 512L * 1024 * 1024) {
            isolatedMemoryBenchmark();
            return;
        }
        assertTrue(Runtime.getRuntime().maxMemory() <= 512L * 1024 * 1024, "isolated qualification JVM must use -Xmx512m");
        var memory = ManagementFactory.getMemoryMXBean(); var peak = new AtomicLong(); var samples = new AtomicLong(); var sampling = new AtomicBoolean(true);
        long before = memory.getHeapMemoryUsage().getUsed();
        var phases = new LinkedHashMap<String,Object>(); var profiles = new LinkedHashMap<String,Object>();
        phases.put("before-corpus", memoryPoint());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var sampler = executor.submit(() -> { while (sampling.get()) { peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max); samples.incrementAndGet(); try { Thread.sleep(5); } catch (InterruptedException stop) { return; } } });
            try {
            f.seedProfile(id(777), 1, 2000, 100, Set.of(OWNER), false, true, true, "renforcé-UTF8-é", 1024, false);
            phases.put("after-grand-fixture", memoryPoint());
            var firstResponse = f.get(f.path(id(777), 1));
            profiles.put("grandHttpBytes", firstResponse.body().length);
            phases.put("after-one-grand-http", memoryPoint());
            if (firstResponse.statusCode() != 200) {
                var service = f.services();
                var ownerField = service.getClass().getDeclaredField("owner"); ownerField.setAccessible(true);
                var readerField = service.getClass().getDeclaredField("pot"); readerField.setAccessible(true);
                var owner = (com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions) ownerField.get(service);
                var reader = (com.kartaguez.pocoma.engine.read.pot.v3.ReadPotV3) readerField.get(service);
                var direct = owner.read(c -> reader.at(f.eui("owner"), TOKEN, id(777), 1, c));
                assertEquals(200, firstResponse.statusCode(), "direct READ diagnostic=" + direct.getClass().getSimpleName());
            }
            assertEquals(200, firstResponse.statusCode());
            await(() -> f.context.getBean(V3ReadTransport.class).availableCapacity() == 2, Duration.ofSeconds(1));
            assertTrue(memory.getHeapMemoryUsage().getMax() - memory.getHeapMemoryUsage().getUsed() > 32L * 1024 * 1024, "headroom before two finite calls");
            var one = executor.submit(() -> f.get(f.path(id(777), 1))); var two = executor.submit(() -> f.get(f.path(id(777), 1)));
            assertEquals(200, one.get(10, TimeUnit.SECONDS).statusCode()); assertEquals(200, two.get(10, TimeUnit.SECONDS).statusCode());
            await(() -> f.context.getBean(V3ReadTransport.class).availableCapacity() == 2, Duration.ofSeconds(1));
            phases.put("after-two-grand-http", memoryPoint());
            f.seedProfile(id(888), 1, 50, 20, Set.of(OWNER), false, true, true, "batch renforcé", 256, true);
            var reinforced = f.post(f.path(id(888), 1) + "/expenses/query", f.batch(50, 1)); assertEquals(200, reinforced.statusCode()); assertEquals(50, f.body(reinforced).get("expenses").size()); profiles.put("reinforcedBatchHttpBytes",reinforced.body().length); phases.put("after-reinforced-batch",memoryPoint());
            f.seedProfile(id(889), 1, 50, 100, Set.of(OWNER), false, true, true, "batch extrême", 1024, true);
            assertEquals(503, f.post(f.path(id(889), 1) + "/expenses/query", f.batch(50, 1)).statusCode()); phases.put("after-extreme-refusal",memoryPoint());
            f.seed(id(778), 1, 0, Set.of(OWNER), false, true, true, "x".repeat(4_194_304)); assertEquals(503, f.get("/pots/" + id(778)).statusCode());
            } finally {
                sampling.set(false); sampler.get(1, TimeUnit.SECONDS);
            }

        } finally { sampling.set(false); }
        assertTrue(samples.get() > 0); assertTrue(peak.get() < Runtime.getRuntime().maxMemory());
        var os = new ProcessBuilder("ps", "-o", "rss=", "-p", Long.toString(ProcessHandle.current().pid())).start(); String rss = new String(os.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim(); assertEquals(0, os.waitFor());
        var gc = ManagementFactory.getGarbageCollectorMXBeans().stream().map(g -> Map.of("name", g.getName(), "collections", g.getCollectionCount(), "milliseconds", g.getCollectionTime())).toList();
        var observation = new LinkedHashMap<String,Object>();
        observation.put("heapMax",Runtime.getRuntime().maxMemory()); observation.put("heapBefore",before); observation.put("heapAfter",memory.getHeapMemoryUsage().getUsed()); observation.put("sampledPeak",peak.get()); observation.put("samples",samples.get()); observation.put("sampleResolutionMillis",5); observation.put("rssKiB",rss); observation.put("gc",gc); observation.put("phases",phases); observation.put("profiles",profiles);
        observation.put("copies","detached JDBC view + DTO + bounded 16KiB segments, no complete encoded-array copy; OS/socket/JDBC internal copies not fully quantified");
        observation.put("allocationScope","cumulative live platform-thread counters by ID; virtual-thread allocation and server-only attribution are not claimed; heap/RSS include JUnit/client/injector");
        f.evidence("Q08-memory", observation);
    }
    void isolatedMemoryBenchmark() throws Exception {
        var directory = java.nio.file.Path.of("target/L5-I1-memory-evidence").toAbsolutePath(); java.nio.file.Files.createDirectories(directory);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var command = List.of(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java").toString(), "-Xmx512m", "-cp", classpath, V3ReadQuotaPostgresTest.class.getName(), "I1_MEMORY_BENCHMARK");
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile());
        builder.environment().put("POCOMA_I1_PHYSICAL", System.getenv().getOrDefault("POCOMA_I1_PHYSICAL", directory.resolve("physical").toString()));
        var child = builder.start(); boolean terminal = child.waitFor(80,TimeUnit.SECONDS);
        if (!terminal) {
            child.descendants().forEach(ProcessHandle::destroy); child.destroy();
            if (!child.waitFor(5,TimeUnit.SECONDS)) { child.descendants().forEach(ProcessHandle::destroyForcibly); child.destroyForcibly(); terminal=child.waitFor(5,TimeUnit.SECONDS); }
            java.nio.file.Files.writeString(directory.resolve("stop.json"), f.json.writeValueAsString(Map.of("reason","independent 80s physical safety bound", "terminationObserved",terminal,"childPid",child.pid())));
            fail("isolated memory benchmark exceeded physical safety bound; preserve child evidence");
        }
        java.nio.file.Files.writeString(directory.resolve("owner.json"), f.json.writeValueAsString(Map.of("childPid",child.pid(),"heapMaximumBytes",512L*1024*1024,"exitCode",child.exitValue(),"command",command,"parentIsJUnitInvocation",true,"childIsDirectOracleExecution",true)));
        assertEquals(0,child.exitValue(),"isolated 512MiB memory oracle failed: "+directory.resolve("child.log"));
    }
    /** The same assertions, not another JUnit invocation, in the bounded physical process. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].equals("I1_MEMORY_BENCHMARK")) throw new IllegalArgumentException("explicit isolated memory benchmark required");
        PG.start(); var scenario=new V3ReadQuotaPostgresTest();
        try { scenario.fixture(); scenario.memoryAndCopies(); }
        finally { try { scenario.close(); } finally { PG.stop(); } }
    }
    Map<String,Object> memoryPoint() throws Exception {
        var bean = ManagementFactory.getPlatformMXBean(com.sun.management.ThreadMXBean.class);
        assertTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        var allocated = new TreeMap<String,Long>(); var http = new TreeMap<String,Long>();
        for (long id : bean.getAllThreadIds()) {
            long bytes = bean.getThreadAllocatedBytes(id); var info = bean.getThreadInfo(id);
            if (bytes >= 0) { allocated.put(Long.toString(id),bytes); if (info != null && info.getThreadName().contains("-exec-")) http.put(Long.toString(id),bytes); }
        }
        var process = new ProcessBuilder("ps","-o","rss=","-p",Long.toString(ProcessHandle.current().pid())).start();
        String rss = new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim(); assertEquals(0,process.waitFor());
        return Map.of("nanoTime",System.nanoTime(),"heapUsed",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),"rssKiB",rss,"platformAllocatedBytesByThread",allocated,"httpWorkerAllocatedBytesByThread",http,"activeReadConnections",f.pool().getHikariPoolMXBean().getActiveConnections(),"httpPermitsAvailable",f.context.getBean(V3ReadTransport.class).availableCapacity());
    }
    static boolean isBusinessQuery(String query) {
        String normalized=query.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        // Keep every business SELECT visible; exclude only the exact maintenance gate probe.
        if(normalized.equals("select state, phase, target_prefix::text as target_prefix_text, operator_id, operation_id, started_at, updated_at, error_detail from public.read_maintenance_state where id = $1")) return false;
        return normalized.startsWith("select") && normalized.contains("read_");
    }
    long businessCalls() { return f.sqlStats().stream().filter(s -> isBusinessQuery(s.get("query").toString())).mapToLong(s -> ((Number) s.get("calls")).longValue()).sum(); }
    @Test void jdbcCountsAndCosts() throws Exception {
        long before = businessCalls(); var batch = f.post(f.path(POT, 1) + "/expenses/query", f.batch(50, 1)); assertEquals(200, batch.statusCode()); assertEquals(3, businessCalls() - before, "binding + exact Pot + grouped subobjects, no N+1");
        f.seed(id(779), 1, 2000, 100, Set.of(OWNER), false, true, true, "grand");
        before = businessCalls(); assertEquals(200, f.get("/pots/" + id(779)).statusCode()); assertEquals(6, businessCalls() - before);
        before = businessCalls(); assertEquals(200, f.get(f.path(id(779), 1)).statusCode()); assertEquals(5, businessCalls() - before);
        long usefulBytes=0; int rows=0; long measuredStart=System.nanoTime(); String plan;
        String sql="select payload::text from read_subobject_version where pot_id=? and local_version=1 and kind in ('EXPENSE_HEADER','EXPENSE_DETAILS','EXPENSE_FINANCIALS')";
        try (var connection=f.pool().getConnection(); var statement=connection.prepareStatement(sql)) {
            statement.setObject(1,POT); try (var result=statement.executeQuery()) { while(result.next()) { rows++; usefulBytes+=result.getString(1).getBytes(StandardCharsets.UTF_8).length; } }
            try(var explain=connection.prepareStatement("explain (analyze,buffers,format json) "+sql)) { explain.setObject(1,POT); try(var result=explain.executeQuery()) { assertTrue(result.next()); plan=result.getString(1); } }
        }
        assertEquals(150,rows); assertTrue(usefulBytes>0);
        var costs=new LinkedHashMap<String,Object>(); costs.put("batchSelects",3); costs.put("currentGrandSelects",6); costs.put("historyGrandSelects",5); costs.put("executionsRowsTimes",f.sqlStats()); costs.put("batchHttpBytes",batch.body().length); costs.put("measuredPayloadRows",rows); costs.put("measuredPayloadUtf8Bytes",usefulBytes); costs.put("measurementSeconds",(System.nanoTime()-measuredStart)/1e9); costs.put("measurementPlan",plan); costs.put("wireFramingBytes","NOT_MEASURED; payload byte count excludes PostgreSQL wire framing"); f.evidence("Q09-jdbc",costs);
    }
    @Test void workersProgressWithoutControlBorrow() throws Exception {
        initializeWorkerDatabase(PG);
        try (var workerFixture = new ReadRuntimeI8Fixture(PG)) {
            workerFixture.select(List.of("MaterializeCurrentBinding"), false);
            var request = workerFixture.admit("REGISTRATION", "i1-worker", "i1-worker", null);
            var context = workerFixture.start();
            var shared = context.getBean("readBusiness", com.zaxxer.hikari.HikariDataSource.class);
            var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
            var acquisitions = new AtomicInteger();
            var observed = new org.springframework.jdbc.datasource.AbstractDataSource() {
                public Connection getConnection() throws SQLException { acquisitions.incrementAndGet(); return wrap(shared.getConnection()); }
                public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
                Connection wrap(Connection connection) {
                    return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(connection, args);
                            if (method.getName().equals("prepareStatement") && args[0].toString().contains("from read_current_binding") && result instanceof PreparedStatement statement)
                                return java.lang.reflect.Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (p, call, values) -> {
                                    try {
                                        Object answer = call.invoke(statement, values);
                                        if (call.getName().equals("executeQuery")) { entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("READ barrier safety timeout"); }
                                        return answer;
                                    } catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
                                });
                            return result;
                        } catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
                    });
                }
            };
            var manager = ReadRuntimeI8Fixture.manager(observed);
            var owner = new com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions(observed, manager, 3);
            var readers = V3ReadFacadeComposition.create(observed, owner, new com.kartaguez.pocoma.contract.readquery.ReadLimitsV3(50,100,16000000), Set.of("MaterializeCurrentBinding"), Optional::empty, true);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var one = executor.submit(() -> readers.binding(f.eui("owner"), 0)); var two = executor.submit(() -> readers.binding(f.eui("owner"), 0));
                try {
                    assertTrue(entered.await(1, TimeUnit.SECONDS), "two actual READ callbacks retained on shared Business pool");
                    assertEquals(2, acquisitions.get()); assertTrue(shared.getHikariPoolMXBean().getActiveConnections() >= 2);
                    assertInstanceOf(Unavailable.class, readers.commandResult(id(401), f.eui("owner"))); assertEquals(2, acquisitions.get());
                    assertEquals(0, workerFixture.read.queryForObject("select count(*) from read_current_binding where subject='i1-worker'", Integer.class));
                    // Commit the source only after both HTTP query callbacks physically retain their connections.
                    workerFixture.produce(request);
                    await(() -> workerFixture.read.queryForObject("select count(*) from read_current_binding where subject='i1-worker'", Integer.class) == 1, Duration.ofSeconds(1));
                    assertTrue(shared.getHikariPoolMXBean().getActiveConnections() >= 2, "worker advanced while readers still retained");
                    f.evidence("Q10-shared-pool", Map.of("poolMaximum", shared.getMaximumPoolSize(), "httpAcquisitionsReadBusiness", acquisitions.get(), "httpControlOrWriteSources", 0, "sourceCommittedAfterReadersRetained", true, "workerResultRowsWhileHeld", 1));
                } finally { release.countDown(); }
                assertInstanceOf(Available.class, one.get(5, TimeUnit.SECONDS)); assertInstanceOf(Available.class, two.get(5, TimeUnit.SECONDS));
            }
            workerFixture.drain(context); workerFixture.evidence("L5-I1-Q10");
        }
    }
}
