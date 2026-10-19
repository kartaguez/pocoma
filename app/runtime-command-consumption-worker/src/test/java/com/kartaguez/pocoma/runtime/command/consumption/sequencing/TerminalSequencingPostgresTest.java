package com.kartaguez.pocoma.runtime.command.consumption.sequencing;

import static org.junit.jupiter.api.Assertions.*;
import static com.kartaguez.pocoma.runtime.command.consumption.sequencing.TerminalSequencingFixture.Family.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.kartaguez.pocoma.runtime.command.consumption.sequencing.TerminalSequencingFixture.Collection;
import com.kartaguez.pocoma.runtime.command.consumption.sequencing.TerminalSequencingFixture.Family;

/** Bounded Step 3B experiment: no production application context. */
@Testcontainers
@TestMethodOrder(MethodOrderer.MethodName.class)
class TerminalSequencingPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
        .withCommand("postgres", "-c", "fsync=on", "-c", "statement_timeout=20000")
        .withDatabaseName("sequencing").withUsername("experiment").withPassword("experiment");
    static DriverManagerDataSource ds;
    static TerminalSequencingFixture f;
    ExecutorService pool;
    @BeforeAll static void schema() {
        ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        f = new TerminalSequencingFixture(ds,true); f.schema();
        System.out.println("STEP3B ENV " + f.sql.queryForMap("select version(), current_setting('synchronous_commit') synchronous_commit, current_setting('fsync') fsync"));
    }
    @BeforeEach void reset() { f.reset(); pool = Executors.newFixedThreadPool(4); }
    @AfterEach void stop() throws Exception {
        pool.shutdownNow(); assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));
        assertThrows(IllegalStateException.class,f::current);
    }
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15,TimeUnit.SECONDS),"barrier timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    static <T> T get(Future<T> future) throws Exception { return future.get(20,TimeUnit.SECONDS); }
    static void blocked(int waiting,int holding) {
        assertNotEquals(waiting,holding);
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<deadline) {
            var rows=f.sql.queryForList("""
                select pid,backend_xid,wait_event_type,wait_event,query,pg_blocking_pids(pid) blockers
                from pg_stat_activity where pid=? and ?=any(pg_blocking_pids(pid)) and wait_event_type='Lock'
                """,waiting,holding);
            if(!rows.isEmpty()) { System.out.println("STEP3B BLOCK " + rows); return; }
            Thread.onSpinWait();
        }
        fail("no PostgreSQL lock wait: " + waiting + " -> " + holding);
    }
    static void noAuthorityLock() {
        assertEquals(0,f.sql.queryForObject(
            "select count(*) from pg_locks where pid=pg_backend_pid() and relation='experiment.authority'::regclass",Integer.class));
    }
    static void continuous(Family family,long n) {
        assertEquals(n,f.counter(family));
        assertEquals(n,f.sql.queryForObject("select count(*) from experiment.event_position where family=?",Long.class,family.name()));
        assertEquals(n,f.sql.queryForObject("""
            select count(*) from generate_series(1,?) n
            join experiment.event_position p on p.family=? and p.position=n
            join experiment.event e on e.family=p.family and e.event_id=p.event_id
            """,Long.class,n,family.name()));
    }
    static List<Long> positions(UUID id) {
        return f.sql.queryForList("select position from experiment.event_position where event_id=?",Long.class,id);
    }
    static void terminalTrace(Collection c) {
        int first=-1;
        for(int i=0;i<c.trace.size();i++) if(c.trace.get(i).startsWith("E:")) { first=i; break; }
        assertTrue(first>c.trace.indexOf("FLUSH"));
        for(String s:c.trace.subList(first,c.trace.size()))
            assertTrue(s.startsWith("E:")||s.startsWith("POSITION:")||s.equals("COMMIT"),c.trace.toString());
        System.out.println("STEP3B TRACE pid="+c.pid+" xid="+c.xid+" "+c.trace);
    }
    @Test void aStartOrderDoesNotAssignPosition() throws Exception {
        CountDownLatch started=new CountDownLatch(1),finish=new CountDownLatch(1);
        AtomicReference<Collection> early=new AtomicReference<>(),late=new AtomicReference<>();
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        Future<?> t1=pool.submit(() -> f.runner.runInTransaction(() -> {
            early.set(f.current()); f.publish(BUSINESS_EVENT,first,"Pot"); started.countDown(); await(finish);
        }));
        try {
            await(started);
            f.runner.runInTransaction(() -> { late.set(f.current()); f.publish(BUSINESS_EVENT,second,"Expense"); });
            assertTrue(early.get().xid<late.get().xid); assertNotEquals(early.get().pid,late.get().pid);
            assertEquals(List.of(1L),positions(second)); assertTrue(positions(first).isEmpty());
        } finally { finish.countDown(); }
        get(t1); assertEquals(List.of(2L),positions(first)); continuous(BUSINESS_EVENT,2);
        terminalTrace(early.get()); terminalTrace(late.get());
    }
    @Test void bRealEWaitThenCommitProgress() throws Exception { waitAndResolve(false); }
    @Test void cRollbackReusesEntireReservation() throws Exception { waitAndResolve(true); }
    void waitAndResolve(boolean rollback) throws Exception {
        CountDownLatch acquired=new CountDownLatch(1),release=new CountDownLatch(1);
        CompletableFuture<Integer> p1=new CompletableFuture<>(),p2=new CompletableFuture<>();
        UUID cancelled=UUID.randomUUID(),next=UUID.randomUUID();
        Future<?> one=pool.submit(() -> f.runner.runInTransaction(() -> {
            Collection c=f.current(); p1.complete(c.pid); f.publish(BUSINESS_EVENT,cancelled,"Pot");
            c.afterFirstE=() -> { acquired.countDown(); await(release); };
            c.afterSuffix=() -> { if(rollback) throw new IllegalStateException("abort after associations"); };
        }));
        Future<?> two=null;
        try {
            await(acquired);
            two=pool.submit(() -> f.runner.runInTransaction(() -> {
                p2.complete(f.current().pid); f.publish(BUSINESS_EVENT,next,"Expense");
            }));
            blocked(get(p2),get(p1)); assertFalse(two.isDone());
            assertEquals(0,f.counter(BUSINESS_EVENT)); assertEquals(0,f.count("event"));
        } finally { release.countDown(); }
        if(rollback) assertInstanceOf(IllegalStateException.class,assertThrows(ExecutionException.class,() -> get(one)).getCause());
        else get(one);
        get(two); assertEquals(List.of(rollback?1L:2L),positions(next));
        assertEquals(rollback?List.of():List.of(1L),positions(cancelled));
        continuous(BUSINESS_EVENT,rollback?1:2); assertEquals(rollback?1:2,f.count("event"));
    }
    @Test void dExactBlockAndDeterministicUuidOrder() {
        List<UUID> ids=List.of(new UUID(0,3),new UUID(0,1),new UUID(0,2));
        AtomicReference<Collection> c=new AtomicReference<>();
        f.runner.runInTransaction(() -> { c.set(f.current()); ids.forEach(id -> f.publish(BUSINESS_EVENT,id,"Pot/Expense")); noAuthorityLock(); });
        assertEquals(List.of(3L),positions(ids.get(0))); assertEquals(List.of(1L),positions(ids.get(1)));
        assertEquals(List.of(2L),positions(ids.get(2))); continuous(BUSINESS_EVENT,3); terminalTrace(c.get());
    }
    @Test void eOppositePublicationUsesOneTotalFamilyOrder() throws Exception {
        CountDownLatch ready=new CountDownLatch(2),firstE=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicReference<Collection> c1=new AtomicReference<>(),c2=new AtomicReference<>();
        Future<?> one=pool.submit(() -> f.runner.runInTransaction(() -> {
            c1.set(f.current()); f.publish(COMMAND_TERMINAL); f.publish(BUSINESS_EVENT); f.publish(USER_CREATED); f.publish(BINDING_FACT);
            noAuthorityLock(); ready.countDown(); await(ready); c1.get().afterFirstE=() -> { firstE.countDown(); await(release); };
        }));
        Future<?> two=pool.submit(() -> f.runner.runInTransaction(() -> {
            c2.set(f.current()); f.publish(BUSINESS_EVENT); f.publish(COMMAND_TERMINAL); f.publish(BINDING_FACT); f.publish(USER_CREATED);
            noAuthorityLock(); ready.countDown(); await(ready); await(firstE);
        }));
        try { await(firstE); blocked(c2.get().pid,c1.get().pid); assertFalse(two.isDone()); }
        finally { release.countDown(); }
        get(one); get(two);
        List<String> expected=Arrays.stream(Family.values()).map(x -> "E:"+x).toList();
        for(Collection c:List.of(c1.get(),c2.get())) {
            assertEquals(expected,c.trace.stream().filter(s -> s.startsWith("E:")).toList()); terminalTrace(c);
        }
        for(Family family:Family.values()) continuous(family,2);
    }
    @Test void fAssociationFailureRollsBackAllFamiliesAndBusiness() {
        assertThrows(RuntimeException.class,() -> f.runner.runInTransaction(() -> {
            f.business("p",1); f.publish(BUSINESS_EVENT); f.publish(COMMAND_TERMINAL); f.current().invalidAssociation=true;
        }));
        assertEquals(0,f.count("event")); assertEquals(0,f.count("event_position"));
        for(Family family:Family.values()) assertEquals(0,f.counter(family));
        assertEquals(0,f.sql.queryForObject("select version from experiment.p where id=1",Integer.class));
        f.runner.runInTransaction(() -> f.publish(BUSINESS_EVENT)); continuous(BUSINESS_EVENT,1);
    }
    @Test void fSavepointsRejectedAndRawBypassFailsClosed() {
        TransactionTemplate nested=new TransactionTemplate(f.owner);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        f.runner.runInTransaction(() -> {
            assertThrows(NestedTransactionNotSupportedException.class,() -> nested.execute(s -> null)); f.publish(BUSINESS_EVENT);
        });
        continuous(BUSINESS_EVENT,1);
        assertThrows(RuntimeException.class,() -> f.runner.runInTransaction(() -> {
            Connection connection=org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds);
            try {
                Savepoint savepoint=connection.setSavepoint(); f.publish(BUSINESS_EVENT); connection.rollback(savepoint);
            } catch(SQLException e) { throw new IllegalStateException(e); }
        }));
        continuous(BUSINESS_EVENT,1); assertEquals(1,f.count("event"));
    }
    @Test void gEmptyTransactionDoesNotTouchOrWaitOnE() throws Exception {
        try(Connection holder=ds.getConnection()) {
            holder.setAutoCommit(false);
            try(Statement s=holder.createStatement()) {
                s.execute("select * from experiment.authority for update");
                Future<Collection> empty=pool.submit(() -> f.runner.runInTransaction(() -> {
                    f.business("p",2);
                    f.current().afterSuffix=() -> assertEquals(0,f.sql.queryForObject(
                        "select count(*) from pg_locks where pid=pg_backend_pid() and relation='experiment.authority'::regclass",Integer.class));
                    return f.current();
                }));
                Collection c=get(empty); assertFalse(c.trace.stream().anyMatch(t -> t.startsWith("E:")));
                for(Family family:Family.values()) assertEquals(0,f.counter(family));
            } finally { holder.rollback(); }
        }
    }
    @Test void hCollectorIsPhysicalAndCleanOnSameThread() {
        List<Collection> collections=new ArrayList<>();
        for(int i=0;i<4;i++) {
            boolean abort=i==1;
            Runnable work=() -> f.runner.runInTransaction(() -> {
                Collection c=f.current(); collections.add(c); f.publish(BUSINESS_EVENT);
                f.runner.runInTransaction(() -> { assertSame(c,f.current()); f.publish(COMMAND_TERMINAL); });
                assertFalse(c.closed); assertFalse(c.trace.stream().anyMatch(t -> t.startsWith("E:")));
                if(abort) throw new IllegalArgumentException("business exception");
            });
            if(abort) assertThrows(IllegalArgumentException.class,work::run); else work.run();
            assertThrows(IllegalStateException.class,f::current);
            Collection old=collections.getLast(); assertTrue(old.events.isEmpty()); assertTrue(old.deferred.isEmpty());
            assertThrows(IllegalStateException.class,old::open);
        }
        assertEquals(4,new HashSet<>(collections).size()); continuous(BUSINESS_EVENT,3); continuous(COMMAND_TERMINAL,3);
        assertThrows(IllegalStateException.class,() -> f.publish(USER_CREATED));
        f.runner.runInTransaction(() -> {
            f.publish(BUSINESS_EVENT); f.current().afterFirstE=() -> {
                assertThrows(IllegalStateException.class,() -> f.publish(USER_CREATED));
                assertThrows(IllegalStateException.class,() -> f.business("p",1));
            };
        });
        assertEquals(0,f.counter(USER_CREATED));
    }
    @Test void iWaitOnERetainsPBSAndPropagatesWaits() throws Exception {
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
        CompletableFuture<Integer> holderPid=new CompletableFuture<>(),commandPid=new CompletableFuture<>();
        List<CompletableFuture<Integer>> followers=new ArrayList<>(); List<Future<?>> futures=new ArrayList<>();
        AtomicReference<Collection> command=new AtomicReference<>();
        Future<?> holder=pool.submit(() -> f.runner.runInTransaction(() -> {
            holderPid.complete(f.current().pid); f.publish(BUSINESS_EVENT);
            f.current().afterFirstE=() -> { held.countDown(); await(release); };
        }));
        try {
            await(held);
            futures.add(pool.submit(() -> f.runner.runInTransaction(() -> {
                command.set(f.current()); commandPid.complete(f.current().pid);
                f.business("p",1); f.business("b",1); f.publish(BUSINESS_EVENT); f.publish(COMMAND_TERMINAL); f.business("s",1);
            })));
            blocked(get(commandPid),get(holderPid));
            for(String table:List.of("p","b","s")) {
                CompletableFuture<Integer> pid=new CompletableFuture<>(); followers.add(pid);
                futures.add(CompletableFuture.runAsync(() -> {
                    try(Connection c=ds.getConnection()) {
                        c.setAutoCommit(false);
                        try(Statement s=c.createStatement()) {
                            try(ResultSet rs=s.executeQuery("select pg_backend_pid()")) { rs.next(); pid.complete(rs.getInt(1)); }
                            s.executeUpdate("update experiment."+table+" set version=version+1 where id=1"); c.rollback();
                        }
                    } catch(SQLException e) { throw new IllegalStateException(e); }
                }));
            }
            for(CompletableFuture<Integer> pid:followers) blocked(get(pid),get(commandPid));
            assertEquals(0,f.sql.queryForObject("select cardinality(pg_blocking_pids(?))",Integer.class,get(holderPid)));
        } finally { release.countDown(); }
        get(holder); for(Future<?> future:futures) get(future);
        terminalTrace(command.get()); continuous(BUSINESS_EVENT,2); continuous(COMMAND_TERMINAL,1);
    }
    @Test void jRunnerAndTransactionalSharePhysicalSuffixAfterFlush() {
        for(boolean annotated:List.of(false,true)) {
            AtomicReference<Collection> c=new AtomicReference<>();
            java.util.function.Supplier<Void> work=() -> {
                c.set(f.current()); f.sql.execute("set local log_statement='all'"); f.publish(USER_CREATED);
                f.current().deferred.add(() -> { f.business("s",3); f.publish(BINDING_FACT); }); return null;
            };
            if(annotated) f.annotated.execute(work); else f.runner.runInTransaction(work);
            terminalTrace(c.get()); assertTrue(c.get().trace.indexOf("S")<c.get().trace.indexOf("FLUSH"));
            physicalSql(c.get());
        }
        continuous(USER_CREATED,2); continuous(BINDING_FACT,2);
    }
    static void physicalSql(Collection c) {
        String log=POSTGRES.getLogs().lines().filter(line -> line.contains("["+c.pid+"]") && line.contains("LOG:"))
            .reduce("",(a,b) -> a+b+"\n");
        int firstE=log.indexOf("update experiment.authority");
        assertTrue(firstE>log.lastIndexOf("insert into experiment.event values"),log);
        assertTrue(firstE>log.indexOf("update experiment.s"),log);
        assertTrue(log.indexOf("COMMIT")>log.lastIndexOf("insert into experiment.event_position"),log);
        assertTrue(firstE>=0,log);
        System.out.println("STEP3B PHYSICAL SQL pid="+c.pid+"\n"+log);
    }
    @Test void jConsumptionOwnerConfirmsCommitAndSuffixRollback() {
        var owner=new com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner(f.runner,true);
        UUID id=owner.runInTransaction(() -> f.publish(BUSINESS_EVENT));
        assertEquals(List.of(1L),positions(id));
        var failure=assertThrows(com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.class,
            () -> owner.runInTransaction(() -> {
                f.publish(BUSINESS_EVENT); f.publish(COMMAND_TERMINAL); f.current().invalidAssociation=true;
            }));
        assertEquals(com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.Outcome.ROLLED_BACK,failure.outcome());
        assertEquals(com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException.Phase.COMMIT,failure.phase());
        continuous(BUSINESS_EVENT,1); assertEquals(0,f.counter(COMMAND_TERMINAL)); assertEquals(1,f.count("event"));
    }
    @Test void jLateSynchronizationPublicationRejectedWithRollback() {
        assertThrows(IllegalStateException.class,() -> f.annotated.execute(() -> {
            f.publish(BUSINESS_EVENT);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) { f.publish(COMMAND_TERMINAL); }
            }); return null;
        }));
        assertEquals(0,f.count("event")); assertEquals(0,f.counter(BUSINESS_EVENT));
    }
    @Test @EnabledIfSystemProperty(named="step3b.measure",matches="true")
    void zExploratoryContentionSeparateFromCorrectness() throws Exception {
        System.out.println("STEP3B MEASURE warmup=8/worker measured=40/worker repeats=3; fresh connection/transaction; mean/median/p95/max/sd in us");
        for(int concurrency:List.of(1,4)) for(int eventCount:List.of(1,8)) for(int families:List.of(1,2)) {
            for(int repeat=0;repeat<3;repeat++) for(boolean enabled:repeat%2==0?List.of(false,true):List.of(true,false)) {
                f.reset(); TerminalSequencingFixture measured=new TerminalSequencingFixture(ds,enabled);
                ExecutorService workers=Executors.newFixedThreadPool(concurrency);
                List<Long> latency=Collections.synchronizedList(new ArrayList<>());
                List<Long> wait=Collections.synchronizedList(new ArrayList<>()),hold=Collections.synchronizedList(new ArrayList<>());
                CountDownLatch ready=new CountDownLatch(concurrency),start=new CountDownLatch(1);
                List<Future<?>> tasks=new ArrayList<>();
                try {
                    for(int worker=0;worker<concurrency;worker++) tasks.add(workers.submit(() -> {
                        for(int i=0;i<8;i++) measureTransaction(measured,eventCount,families,null,null,null);
                        ready.countDown(); await(start);
                        for(int i=0;i<40;i++) measureTransaction(measured,eventCount,families,latency,wait,hold);
                    }));
                    await(ready); long begin=System.nanoTime(); start.countDown();
                    for(Future<?> task:tasks) task.get(60,TimeUnit.SECONDS);
                    double seconds=(System.nanoTime()-begin)/1e9;
                    assertEquals((8+40)*concurrency*eventCount*families,measured.count("event"));
                    if(enabled) for(Family family:families==1?List.of(BUSINESS_EVENT):List.of(BUSINESS_EVENT,COMMAND_TERMINAL))
                        continuous(family,(8+40)*concurrency*eventCount);
                    else assertEquals(0,measured.count("event_position"));
                    System.out.printf(Locale.ROOT,"STEP3B CSV,%d,%d,%d,%d,%s,%d,%.3f,%s,%s,%s%n",repeat,concurrency,eventCount,families,
                        enabled,latency.size(),latency.size()/seconds,stats(latency),stats(wait),stats(hold));
                } finally { start.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(15,TimeUnit.SECONDS)); }
            }
        }
    }
    static void measureTransaction(TerminalSequencingFixture m,int events,int families,List<Long> latency,List<Long> wait,List<Long> hold) {
        long begin=System.nanoTime();
        Collection c=m.runner.runInTransaction(() -> {
            for(int i=0;i<events;i++) { m.publish(BUSINESS_EVENT); if(families==2) m.publish(COMMAND_TERMINAL); }
            return m.current();
        });
        if(latency!=null) {
            latency.add(System.nanoTime()-begin); wait.add(c.waitNanos); hold.add(c.acquiredNanos==0?0:c.endNanos-c.acquiredNanos);
        }
    }
    static String stats(List<Long> values) {
        long[] a=values.stream().mapToLong(Long::longValue).sorted().toArray();
        double mean=Arrays.stream(a).average().orElseThrow();
        double sd=Math.sqrt(Arrays.stream(a).mapToDouble(v -> (v-mean)*(v-mean)).average().orElseThrow());
        return String.format(Locale.ROOT,"%.1f/%.1f/%.1f/%.1f/%.1f",mean/1000,a[(a.length-1)/2]/1000.0,
            a[(int)Math.ceil(a.length*.95)-1]/1000.0,a[a.length-1]/1000.0,sd/1000);
    }
}
