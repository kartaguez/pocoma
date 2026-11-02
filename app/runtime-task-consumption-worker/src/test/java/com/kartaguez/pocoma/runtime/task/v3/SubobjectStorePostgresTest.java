package com.kartaguez.pocoma.runtime.task.v3;

import static org.junit.jupiter.api.Assertions.*;
import static com.kartaguez.pocoma.runtime.task.v3.SubobjectPostgresFixture.*;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.infra.persistence.read.jdbc.v3.*;
import com.kartaguez.pocoma.port.projection.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Testcontainers
class SubobjectStorePostgresTest {
    @Container static final PostgreSQLContainer PG = new PostgreSQLContainer(IMAGE).withDatabaseName("i1_read");
    @BeforeAll static void init() { initialize(PG); }

    @Test void identicalInsert() throws Exception {
        try (var f=new SubobjectPostgresFixture(PG,"identicalInsert")) {
            var values=eight(UUID.randomUUID()); f.insert(values); f.insert(values);
            assertEquals(8,f.count()); assertEquals(new HashSet<>(values),new HashSet<>(f.read(values).values()));
            assertEquals("I1 exact", f.oracle.queryForObject("select payload->>'label' from read_subobject_version where kind='POT_DETAILS'",String.class));
            assertEquals("123",f.oracle.queryForObject("select payload->'amount'->>'n' from read_subobject_version where kind='EXPENSE_FINANCIALS'",String.class));
            assertEquals(0,f.oracle.queryForObject("select count(*) from consumption_slot",Integer.class));
        }
    }
    @Test void divergentInsert() throws Exception {
        try (var f=new SubobjectPostgresFixture(PG,"divergentInsert")) {
            var p=UUID.randomUUID(); var original=header(p,1,false); f.insert(List.of(original));
            var failure=assertThrows(AttemptTransactions.Failure.class,()->f.insert(List.of(header(p,1,true))));
            assertInstanceOf(SubobjectConflictException.class,failure.getCause());
            assertEquals(AttemptTransactions.Resolution.ROLLED_BACK,failure.resolution());
            assertEquals(Map.of(original.key(),original),f.read(List.of(original))); assertEquals(1,f.count());
        }
    }
    @Test void allFieldsMatter() throws Exception {
        try (var f=new SubobjectPostgresFixture(PG,"allFieldsMatter")) {
            var values=eight(UUID.randomUUID()); f.insert(values);
            String[][] changes={{"POT_HEADER","{\"deleted\":true}"},{"POT_DETAILS","{\"label\":\"other\",\"creatorId\":\"00000000-0000-0000-0000-000000000004\"}"},
                {"POT_DETAILS","{\"label\":\"I1 exact\",\"creatorId\":\"00000000-0000-0000-0000-000000000009\"}"},
                {"SHAREHOLDER_HEADER","{\"deleted\":true}"},{"SHAREHOLDER_DETAILS","{\"name\":\"Alice\",\"userId\":\"00000000-0000-0000-0000-000000000004\"}"},
                {"SHAREHOLDER_DETAILS","{\"name\":\"alice\",\"userId\":null}"},{"SHAREHOLDER_WEIGHT","{\"defaultWeight\":{\"n\":\"1\",\"d\":\"3\"}}"},
                {"EXPENSE_HEADER","{\"deleted\":true}"},{"EXPENSE_DETAILS","{\"label\":\"Expense\",\"date\":\"2026-11-03\"}"},
                {"EXPENSE_DETAILS","{\"label\":\"expense\",\"date\":\"2026-11-02\"}"}};
            var divergent=new ArrayList<SubobjectVersion>();
            for (var change:changes) divergent.add(SubobjectJson.decode(values.get(SubobjectKind.valueOf(change[0]).rank()).key(),1,change[1]));
            var financials=values.get(7); String json=SubobjectJson.encode(financials);
            for (String candidate:List.of(json.replace("123","124"),json.replace("\"payerId\":\"00000000-0000-0000-0000-000000000002\"","\"payerId\":\"00000000-0000-0000-0000-000000000009\""),
                    json.replace("\"n\":\"7\",\"d\":\"3\"","\"n\":\"8\",\"d\":\"3\""),json.replace("00000000-0000-0000-0000-000000000005","00000000-0000-0000-0000-000000000006"),
                    json.replace("\"specificWeight\":null","\"specificWeight\":{\"n\":\"1\",\"d\":\"1\"}"))) divergent.add(SubobjectJson.decode(financials.key(),1,candidate));
            for (var value:divergent) {
                assertNotEquals(values.get(value.key().kind().rank()),value);
                var failure=assertThrows(AttemptTransactions.Failure.class,()->f.insert(List.of(value)));
                assertInstanceOf(SubobjectConflictException.class,failure.getCause());
            }
            assertEquals(new HashSet<>(values),new HashSet<>(f.read(values).values())); assertEquals(8,f.count());
        }
    }
    @Test void batchAllOrNothing() throws Exception {
        try (var f=new SubobjectPostgresFixture(PG,"batchAllOrNothing")) {
            var p=UUID.randomUUID(); var preexisting=header(p,600,true); f.insert(List.of(preexisting));
            var values=f.batch(p,600); var failure=assertThrows(AttemptTransactions.Failure.class,()->f.insert(values));
            assertInstanceOf(SubobjectConflictException.class,failure.getCause());
            assertEquals(1,f.count()); assertEquals(preexisting,f.read(List.of(preexisting)).get(preexisting.key()));
            var missing=header(p,900,false);
            assertEquals(Map.of(),f.read(List.of(missing)));
            var success=f.batch(UUID.randomUUID(),600); f.insert(success); assertEquals(601,f.count()); assertEquals(600,f.read(success).size());
            assertTrue(f.source.statements.stream().filter(s->s.startsWith("insert into read_subobject_version")).allMatch(s->s.chars().filter(c->c=='?').count()<=256*5));
            f.tx.write(()->{f.store.insertOrVerifyAll(List.of());f.store.requireStored(List.of());return null;});
            assertEquals(Map.of(),f.tx.read(c->f.store.readAll(List.of(),c)));
            for (List<SubobjectVersion> bad:List.of(List.of(success.getFirst(),success.getFirst()),Arrays.asList(success.getFirst(),null))) {
                var rejected=assertThrows(AttemptTransactions.Failure.class,()->f.tx.write(()->{
                    try { f.store.insertOrVerifyAll(bad); } catch (RuntimeException intercepted) { f.trace.add("caught="+intercepted.getClass()); }
                    return null;
                }));
                assertEquals(AttemptTransactions.Resolution.ROLLED_BACK,rejected.resolution());
            }
        }
    }
    void race(boolean divergent,boolean winnerRollsBack, boolean reverseBatch) throws Exception {
        try (var f=new SubobjectPostgresFixture(PG,"race-"+divergent+"-"+winnerRollsBack+"-"+reverseBatch)) {
            var p=UUID.randomUUID(); var values=reverseBatch ? f.batch(p,600) : List.of(header(p,1,false));
            var candidates=new ArrayList<>(values);
            if (divergent) candidates.set(0,header(p,1,true));
            if (reverseBatch) Collections.reverse(candidates);
            var inserted=new CountDownLatch(1); var release=new CountDownLatch(1); var loserStarted=new CountDownLatch(1); var pid=new AtomicInteger();
            var winner=f.threads.submit(()->{
                try { f.tx.write(()->{f.store.insertOrVerifyAll(values);inserted.countDown();await(release);if(winnerRollsBack)throw new IllegalStateException("winner rollback");return null;});return null; }
                catch (AttemptTransactions.Failure failure) {return failure;}
            });
            await(inserted);
            var loserThread=new AtomicLong();
            var loser=f.threads.submit(()->{
                loserThread.set(Thread.currentThread().threadId());
                try { f.tx.write(()->{pid.set(((Number)f.witness(f.source,"loser-before-insert").get("pid")).intValue());loserStarted.countDown();f.store.insertOrVerifyAll(candidates);return null;});return null; }
                catch (AttemptTransactions.Failure failure) {return failure;}
            });
            try { await(loserStarted);f.waitForLock(pid.get());assertFalse(loser.isDone()); }
            finally { release.countDown(); }
            var won=get(winner);var lost=get(loser);
            if (winnerRollsBack) { assertNotNull(won); assertNull(lost); }
            else if (divergent) { assertNull(won);assertNotNull(lost);assertInstanceOf(SubobjectConflictException.class,lost.getCause()); }
            else {assertNull(won);assertNull(lost);}
            var expected=winnerRollsBack ? candidates : values;
            assertEquals(values.size(),f.count());assertEquals(new HashSet<>(expected),new HashSet<>(f.read(expected).values()));
            f.trace.add("winner rollback="+winnerRollsBack+" divergent="+divergent+" final="+f.read(expected));
            // On the loser thread an INSERT and a later separate SELECT were both actually executed.
            var statements=f.source.executions.stream().filter(e->e.thread()==loserThread.get()).map(e->e.sql()).toList();
            int insert=-1,select=-1;
            for(int i=0;i<statements.size();i++) {
                if(insert<0 && statements.get(i).startsWith("insert into read_subobject_version"))insert=i;
                if(select<0 && statements.get(i).startsWith("select pot_id,kind"))select=i;
            }
            assertTrue(insert>=0 && select>insert,"loser must execute its own SELECT after its conflicted INSERT");
            f.trace.add("loser SQL instructions="+statements);
        }
    }
    @Test void concurrentIdentical() throws Exception { race(false,false,false); }
    @Test void concurrentDivergent() throws Exception { race(true,false,false);race(true,true,false); }
    @Test void conflictThenCommittedRead() throws Exception { race(true,false,false); }
    @Test void reversedBatchesUseTotalOrder() throws Exception { race(false,false,true); }
    @Test void postgresUuidOrder() throws Exception {
        try (var f=new SubobjectPostgresFixture(PG,"postgresUuidOrder")) {
            var ids=List.of(new UUID(Long.MIN_VALUE,0),new UUID(Long.MAX_VALUE,-1),new UUID(0,Long.MIN_VALUE),new UUID(0,Long.MAX_VALUE));
            var db=f.oracle.queryForList("select id from (values (?::uuid),(?::uuid),(?::uuid),(?::uuid)) t(id) order by id",UUID.class,ids.toArray());
            assertEquals(ids.stream().sorted(SubobjectKey::compareUuid).toList(),db); f.trace.add("uuid order="+db);
        }
    }
    @Test void strictCodec() throws Exception {
        try (var f=new SubobjectPostgresFixture(PG,"corruptPersistedContent")) {
            var v=eight(UUID.randomUUID()).get(4);
            f.oracle.update("insert into read_subobject_version values (?,?,?,?,1,'{\"defaultWeight\":{\"n\":\"2\",\"d\":\"4\"}}')",v.key().potId(),v.key().kind().name(),v.key().objectId(),v.key().localVersion());
            assertThrows(AttemptTransactions.Failure.class,()->f.read(List.of(v)));
            var fail=assertThrows(AttemptTransactions.Failure.class,()->f.insert(List.of(v)));assertInstanceOf(CorruptSubobjectException.class,fail.getCause());
            assertEquals("2",f.oracle.queryForObject("select payload->'defaultWeight'->>'n' from read_subobject_version",String.class));
        }
    }
    @Test void ownerAndContext() throws Exception {
        try (var f=new SubobjectPostgresFixture(PG,"ownerAndContext")) {
            var v=header(UUID.randomUUID(),1,false);
            assertThrows(RuntimeException.class,()->f.store.insertOrVerifyAll(List.of(v)));
            assertThrows(RuntimeException.class,()->f.store.requireStored(List.of()));
            assertThrows(IllegalArgumentException.class,()->new JdbcSubobjectVersionStore(f.datasource(f.runtimeRole),f.tx));
            var second=f.datasource(f.runtimeRole);var foreignOwner=owner(second);var foreign=new JdbcSubobjectVersionStore(second,foreignOwner);
            var failure=assertThrows(AttemptTransactions.Failure.class,()->f.tx.write(()->{try {foreign.insertOrVerifyAll(List.of(v));}catch(RuntimeException caught){}return null;}));
            assertEquals(AttemptTransactions.Resolution.ROLLED_BACK,failure.resolution());assertEquals(0,f.count());
            var saved=new AtomicReference<RecoveryReadContext>();
            var poisonedRead=assertThrows(AttemptTransactions.Failure.class,()->f.tx.read(c->{saved.set(c);assertEquals(Map.of(),f.store.readAll(List.of(v.key()),c));
                assertThrows(RuntimeException.class,()->foreign.readAll(List.of(v.key()),c));
                assertThrows(ExecutionException.class,()->get(f.threads.submit(()->f.store.readAll(List.of(v.key()),c))));return null;}));
            assertEquals(AttemptTransactions.Resolution.ROLLED_BACK,poisonedRead.resolution());
            assertInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class,poisonedRead.getCause());
            assertEquals(0,f.count());
            f.trace.add("caught foreign/async observation: physical="+poisonedRead.resolution()+" cause="+poisonedRead.getCause());
            assertThrows(RuntimeException.class,()->f.store.readAll(List.of(v.key()),saved.get()));
            var manager=new DataSourceTransactionManager(f.source);manager.setNestedTransactionAllowed(false);
            var independent=new TransactionTemplate(manager);independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThrows(AttemptTransactions.Failure.class,()->f.tx.write(()->{try {independent.execute(s->{f.store.insertOrVerifyAll(List.of(v));return null;});}catch(RuntimeException caught){}return null;}));
            assertEquals(0,f.count());
        }
    }
}
