package com.kartaguez.pocoma.infra.postgres.write.v3;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionFailure;
import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;
import com.kartaguez.pocoma.contract.writeadmission.LinkProposalInput;

import static org.junit.jupiter.api.Assertions.*;
import com.kartaguez.pocoma.contract.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.contract.writeadmission.*;
import com.kartaguez.pocoma.contract.writeadmission.*;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.v3.BindingToken;
import com.kartaguez.pocoma.engine.admit.identity.v3.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.lang.reflect.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.AbstractDataSource;

/** Physical role/SQL observations, with independent connections for races and rollback. */
@Testcontainers
class V3LinkAdmissionPostgresTest {
    @Container static final PostgreSQLContainer PG = V3AdmissionPostgresTest.PG;
    V3AdmissionPostgresTest f;
    @BeforeEach void setup() throws Exception { f = new V3AdmissionPostgresTest(); f.setup(); }
    AdmitLinkProposal service() { return service(f.store, new AdmissionSqlTransactions(f.source, f.owner), Duration.ofMinutes(10)); }
    AdmitLinkProposal service(AdmitLinkProposal.Store store, com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions tx, Duration ttl) {
        return new AdmitLinkProposal(store, tx, f.protection, Clock.systemUTC(), ttl, 4096);
    }
    AdmitLinkProposal on(DataSource source) {
        var owner = V3AdmissionPostgresTest.owner(source);
        return service(new JdbcAdmissionStore(source, owner), new AdmissionSqlTransactions(source, owner), Duration.ofMinutes(10));
    }
    LinkProposalInput input(String target, String token) {
        return LinkProposalInput.decode(Map.of("bindingToken", token, "target", Map.of("issuer", "issuer", "subject", target)), new ExternalIdentity("issuer", "E1"));
    }
    UUID create(String key) { return service().create(f.principal("E1"), key, input("E2", "SECRET-E1")).linkRequestId(); }
    void status(int expected, Runnable action) { assertEquals(expected, assertThrows(AdmissionFailure.class, action::run).status()); }
    void counts(long proposals, long admissions) {
        assertEquals(proposals, f.count("v3_link_proposal"));
        assertEquals(admissions, f.count("v3_identity_request"));
        assertEquals(admissions, f.count("v3_admission_association"));
        for (String table : List.of("consumption_slot", "v3_eui_authority", "v3_eui_version", "v3_identity_event")) assertEquals(0, f.count(table), table);
    }
    @Test void proposalCreateReplayAndOwnership() {
        var id = create("same");
        assertEquals(id, create("same"));
        status(409, () -> service().create(f.principal("E1"), "same", input("E3", "SECRET-E1")));
        assertEquals("PENDING", service().get(f.principal("E1"), id).state());
        assertEquals("PENDING", service().get(f.principal("E2"), id).state());
        status(404, () -> service().get(f.principal("E3"), id));
        status(404, () -> service().accept(f.principal("E1"), id));
        var differentIssuer = new AuthenticatedExternalPrincipal("different issuer", "E2", Instant.now().minusSeconds(2), Instant.now().minusSeconds(1), Instant.now().plusSeconds(60), Set.of());
        status(404, () -> service().accept(differentIssuer, id));
        counts(1, 0);
    }
    @Test void proposalAndAdmissionIntegrity() {
        var id = create("key");
        for (String mutation : List.of("update v3_link_proposal set target_subject='E3'", "delete from v3_link_proposal",
                "update v3_link_proposal set expires_at=expires_at+interval '1 hour'",
                "update v3_link_proposal set accepted_at=clock_timestamp(),identity_request_id=link_request_id"))
            assertThrows(RuntimeException.class, () -> f.observer.execute(mutation));
        var request = new IdentityRequest(UUID.randomUUID(), new ExternalIdentity("issuer", "E1"),
                new IdentityRequest.Link(new ExternalIdentity("issuer", "E2")), new BindingToken("SECRET-E1"), Instant.now(),
                new IdentityRequest.AdmissionContext("pocoma-v3-link-post", "1", new ExternalIdentity("issuer", "E1"),
                    new ExternalIdentity("issuer", "E2"), "fingerprint", "correlation", Instant.now(), Map.of(), IdentityRequest.Origin.AUTHENTICATED_HTTP));
        assertThrows(RuntimeException.class, () -> f.owner.write(() -> { f.store.insertAcceptedLink(request); return null; }));
        counts(1, 0);
        service().accept(f.principal("E2"), id); counts(1, 1);
        assertThrows(RuntimeException.class, () -> f.observer.execute("update v3_link_proposal set accepted_at=accepted_at+interval '1 second'"));
        // Even administrator mistakes cannot produce a preaccepted proposal or change intent.
        assertThrows(RuntimeException.class, () -> f.observer.execute("insert into v3_link_proposal select * from v3_link_proposal"));
    }
    @Test void deferredGuardRejectsSubstitutedIntention() {
        for (String field : List.of("binding_token", "target_subject", "requester_subject")) {
            var id = create(field);
            var changed = (AdmitLinkProposal.Store) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{AdmitLinkProposal.Store.class}, (p,m,a) -> {
                if (m.getName().equals("insertAcceptedLink")) {
                    var r = (IdentityRequest)a[0];
                    var initiator = field.equals("requester_subject") ? new ExternalIdentity("issuer", "E3") : r.requesterEui();
                    var target = field.equals("target_subject") ? new ExternalIdentity("issuer", "E3") : r.target();
                    var c = r.admission();
                    var context = new IdentityRequest.AdmissionContext(c.validator(),c.contractVersion(),initiator,target,c.intentionFingerprint(),c.grantOrCorrelationReference(),c.validatedAt(),c.attestations(),c.origin());
                    a[0] = new IdentityRequest(r.requestId(),initiator,new IdentityRequest.Link(target),field.equals("binding_token") ? new BindingToken("SUBSTITUTED") : r.bindingToken(),r.createdAt(),context);
                }
                try { return m.invoke(f.store,a); } catch (InvocationTargetException error) { throw error.getCause(); }
            });
            status(503, () -> service(changed, new AdmissionSqlTransactions(f.source,f.owner),Duration.ofMinutes(10)).accept(f.principal("E2"),id));
        }
        counts(3,0);
    }
    void awaitLock() {
        long deadline = System.nanoTime()+Duration.ofSeconds(4).toNanos();
        while (System.nanoTime()<deadline) {
            if (f.observer.queryForObject("select exists(select 1 from pg_stat_activity where usename=? and wait_event_type='Lock')",Boolean.class,f.role)) return;
            java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);
        }
        fail("No independent PostgreSQL lock waiter observed");
    }
    void expiryWait(boolean jwt) throws Exception {
        var shortService = service(f.store,new AdmissionSqlTransactions(f.source,f.owner),Duration.ofSeconds(jwt ? 600 : 1));
        var id = shortService.create(f.principal("E1"),"expiry",input("E2","SECRET-E1")).linkRequestId();
        Instant expiry = jwt ? Instant.now().plusSeconds(1) : f.observer.queryForObject("select expires_at from v3_link_proposal",Timestamp.class).toInstant();
        var actor = jwt ? new AuthenticatedExternalPrincipal("issuer","E2",Instant.now().minusSeconds(2),Instant.now().minusSeconds(1),expiry,Set.of()) : f.principal("E2");
        try (var lock = f.source.getConnection(); var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            lock.setAutoCommit(false);
            lock.createStatement().execute("select link_request_id from v3_link_proposal where link_request_id='"+id+"' for update");
            var result = threads.submit(() -> { status(jwt ? 401 : 410, () -> shortService.accept(actor,id)); return true; });
            awaitLock();
            long deadline = System.nanoTime()+Duration.ofSeconds(4).toNanos();
            while (Instant.now().isBefore(expiry) && System.nanoTime()<deadline) java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);
            assertFalse(Instant.now().isBefore(expiry)); lock.commit();
            assertTrue(result.get(5,TimeUnit.SECONDS));
        }
        counts(1,0);
    }
    @Test void expiryAfterLockWait() throws Exception { expiryWait(false); }
    @Test void jwtExpiryAfterLockWait() throws Exception { expiryWait(true); }
    @Test void simultaneousAcceptance() throws Exception {
        var id=create("race");
        try (var threads=Executors.newVirtualThreadPerTaskExecutor(); var lock=f.source.getConnection()) {
            lock.setAutoCommit(false); lock.createStatement().execute("select link_request_id from v3_link_proposal for update");
            var barrier=new CyclicBarrier(3);
            Callable<IssueLinkProposal.Accepted> action=()->{barrier.await(5,TimeUnit.SECONDS);return service().accept(f.principal("E2"),id);};
            var a=threads.submit(action);var b=threads.submit(action);barrier.await(5,TimeUnit.SECONDS);awaitLock();lock.commit();
            assertEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS));
        }
        counts(1,1);
        assertEquals("E1",f.observer.queryForObject("select requester_subject from v3_identity_request",String.class));
        assertEquals(id,service().accept(f.principal("E2"),id).requestId());
    }
    @Test void longOpaqueIntentionDoesNotRequireWideIndex() {
        String target = UUID.randomUUID().toString().repeat(100);
        String token = UUID.randomUUID().toString().repeat(100);
        var id = service().create(f.principal("E1"), "long", input(target, token)).linkRequestId();
        service().accept(f.principal(target), id);
        assertEquals(token, f.observer.queryForObject("select binding_token from v3_identity_request", String.class));
        assertEquals(target, f.observer.queryForObject("select target_subject from v3_identity_request", String.class));
        counts(1, 1);
    }
    @Test void rollbackAtEveryAdmissionBoundary() {
        for (String boundary:List.of("reserve","insertAcceptedLink","accept")) {
            var id=create(boundary);
            var broken=(AdmitLinkProposal.Store)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{AdmitLinkProposal.Store.class},(p,m,a)->{
                try {var value=m.invoke(f.store,a);if(m.getName().equals(boundary))throw new IllegalStateException("injected boundary");return value;}
                catch(InvocationTargetException error){throw error.getCause();}
            });
            status(503,()->service(broken,new AdmissionSqlTransactions(f.source,f.owner),Duration.ofMinutes(10)).accept(f.principal("E2"),id));
            assertNull(f.observer.queryForObject("select identity_request_id from v3_link_proposal where link_request_id=?",UUID.class,id));
        }
        counts(3,0);
        var id=create("commit-rollback");status(503,()->on(f.faultyCommit(false,null,null)).accept(f.principal("E2"),id));counts(4,0);
        service().accept(f.principal("E2"),id);counts(4,1);
    }
    @Test void lostCommitAndRetryDoNotInventResult() {
        var id=create("unknown");status(503,()->on(f.faultyCommit(true,null,null)).accept(f.principal("E2"),id));
        counts(1,1);assertEquals(id,service().accept(f.principal("E2"),id).requestId());counts(1,1);
    }
    @Test void unavailablePhysicalObserverFailsClosed() {
        var id=create("observer");var ended=new AtomicBoolean();var faulty=f.faultyCommit(true,null,null);
        var unavailable=new AbstractDataSource(){
            public Connection getConnection() throws SQLException {
                if(ended.get())throw new SQLException("observer unavailable","08006");var c=faulty.getConnection();
                return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                    try{return m.invoke(c,a);}catch(InvocationTargetException error){if(m.getName().equals("commit")&&!c.isReadOnly())ended.set(true);throw error.getCause();}
                });
            }
            public Connection getConnection(String u,String p)throws SQLException{return getConnection();}
        };
        var failure=assertThrows(AdmissionFailure.class,()->on(unavailable).accept(f.principal("E2"),id));
        assertEquals(503,failure.status());assertFalse(failure.physicalTerminationObserved());counts(1,1);
        assertEquals(id,service().accept(f.principal("E2"),id).requestId());
    }
    @Test void effectiveAclAndSecretChannels() throws Exception {
        AdmissionSqlAuthority.verify(f.source,PG.getDatabaseName(),f.schema,f.role);
        String canary="ACL-SECRET-"+UUID.randomUUID();
        service().create(f.principal("E1"),"acl",input("E2",canary));
        try(var connection=f.source.getConnection();var sql=connection.createStatement()) {
            for(String query:List.of("select binding_token from v3_eui_version","select admission_context from v3_identity_request",
                    "update v3_link_proposal set binding_token='"+canary+"'","delete from v3_link_proposal")) assertThrows(SQLException.class,()->sql.execute(query));
        }
        assertFalse(service().get(f.principal("E2"),f.observer.queryForObject("select link_request_id from v3_link_proposal",UUID.class)).toString().contains("SECRET-E1"));
        f.observer.execute("grant update(binding_token) on v3_link_proposal to "+f.role);
        assertThrows(IllegalStateException.class,()->AdmissionSqlAuthority.verify(f.source,PG.getDatabaseName(),f.schema,f.role));
        assertFalse(PG.getLogs().contains(canary));
        counts(1,0);
    }
    @Test void linkAcceptancePublishesSequentialPositionAsFinalStatementBeforeCommit() {
        var id1 = create("seq-1");
        var id2 = create("seq-2");
        var order = new ArrayList<String>();
        var tracedStore = (AdmitLinkProposal.Store) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{AdmitLinkProposal.Store.class},
                (p, m, a) -> {
                    order.add(m.getName());
                    return m.invoke(f.store, a);
                });
        var svc = service(tracedStore, new AdmissionSqlTransactions(f.source, f.owner), Duration.ofMinutes(10));
        assertEquals(id1, svc.accept(f.principal("E2"), id1).requestId());
        assertEquals(List.of("proposal", "now", "reserve", "accept", "insertAcceptedLink"), order);
        assertEquals(id2, service().accept(f.principal("E2"), id2).requestId());
        // Replay does not allocate a new position
        assertEquals(id1, service().accept(f.principal("E2"), id1).requestId());
        assertEquals(2L, f.observer.queryForObject(
                "select position from v3_request_counter where family='IDENTITY_REQUEST'", Long.class));
        assertEquals(List.of(id1, id2), f.observer.queryForList(
                "select request_id from v3_request_position where family='IDENTITY_REQUEST' order by position", UUID.class));
    }
}
