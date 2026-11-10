package com.kartaguez.pocoma.runtime.task.v3;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.useridentity.v3.BindingToken;
import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.engine.read.commandresult.v3.GetCommandTerminalResult;
import com.kartaguez.pocoma.engine.read.registrationresult.v3.GetIdentityTerminalResult;
import com.kartaguez.pocoma.infra.persistence.read.jdbc.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.kartaguez.pocoma.runtime.consumption.v3.V3ReadParticipants;
import com.kartaguez.pocoma.runtime.consumption.v3.V3ReadParticipants.Name;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@Testcontainers
class ReadParticipantsIntegrationPostgresTest {
    @Container static final PostgreSQLContainer PG=ReadParticipantsContainer.create();
    @BeforeAll static void init() { SubobjectPostgresFixture.initialize(PG); }

    ConsumptionParticipant decorated(ConsumptionParticipant actual,ReadParticipantsFixture f,String mode,AtomicInteger sourceDuringFinal) {
        return new ConsumptionParticipant() {
            public Optional<Prepared> prepare(UUID id,PreparationReads reads) {
                return actual.prepare(id,reads).map(p -> new Prepared() {
                    public void requireCompletionReady() { p.requireCompletionReady(); }
                    public void finalizeIn(SlotCompletion completion) {
                        var prior=f.f.primary.before;
                        f.f.primary.before=sql -> { sourceDuringFinal.incrementAndGet(); throw new AssertionError("WRITE SQL during final READ: "+sql); };
                        try {
                            if(mode.equals("missing")) { try { completion.complete(); } catch(RuntimeException expected) { f.f.read.trace.add("caught missing business readiness "+expected.getClass().getSimpleName()); } }
                            else p.finalizeIn(() -> {
                                completion.complete();
                                if(mode.equals("afterComplete")) {
                                    try { SpringAttemptTransactions.guarded(f.f.read.source,() -> { throw new IllegalStateException("injected caught writer failure after DELETE"); }); }
                                    catch(RuntimeException expected) { f.f.read.trace.add("caught writer poison after complete"); }
                                }
                            });
                        } finally { f.f.primary.before=prior; }
                    }
                });
            }
            public Recovery prepareRecovery(UUID id,PreparationReads reads) { return actual.prepareRecovery(id,reads); }
        };
    }

    @ParameterizedTest
    @ValueSource(strings={"POT_VERSION","POT_BALANCE","POT_VISIBILITY","CURRENT_BINDING","COMMAND_RESULT","IDENTITY_REQUEST_RESULT"})
    void participantConformance(String participant) throws Exception {
        var name=Name.valueOf(participant);
        for(var mode:List.of("missing","afterComplete","success")) try(var f=new ReadParticipantsFixture(PG,participant+"-"+mode)) {
            f.enroll(); f.witnesses(); var sourceDuringFinal=new AtomicInteger();
            var real=f.participants.get(name).participant(); var engine=f.engine(name,decorated(real,f,mode,sourceDuringFinal),f.f.read.source,f.f.read.tx);
            if(name==Name.CURRENT_BINDING) {
                var ignored=f.f.oracle.queryForObject("select event_id from v3_identity_event where subject_type='POCOMA_USER' and request_id=?",UUID.class,f.identityRequest);
                assertEquals(0,f.slots(name,ignored),"POCOMA_USER filtered before slot enrollment");
                assertEquals(RecoveryObservation.INCONSISTENT,engine.observe(new ClaimedSlot(f.participants.get(name).definition().id(),ignored,UUID.randomUUID())),"irrelevant publication cannot justify a fabricated completion");
            }
            UUID event=f.event(name); var acquired=f.acquire(name,engine,event); var before=f.vector();
            assertEquals(RecoveryObservation.NOT_COMPLETED,engine.observe(acquired.claim()),participant+" before effects");
            var result=engine.execute(acquired);
            assertEquals(0,sourceDuringFinal.get());
            if(!mode.equals("success")) {
                assertEquals(AttemptTransactions.Resolution.ROLLED_BACK,result.resolution(),()->String.valueOf(result));
                assertEquals(before,f.vector(),participant+" no partial business prefix "+mode); assertEquals(1,f.slots(name,event));
                assertEquals(0,f.f.read.oracle.queryForObject("select count(*) from l4_witness",Integer.class));
            } else {
                assertEquals(ConsumptionEngine.Status.COMMITTED,result.status(),()->String.valueOf(result.cause()));
                assertEquals(0,f.slots(name,event));
                assertEquals(RecoveryObservation.COMPLETED,engine.observe(acquired.claim()));
                var witness=f.f.read.oracle.queryForList("select * from l4_witness");
                assertTrue(witness.size()>=2,participant+" physical effect and slot witnesses");
                assertEquals(1,witness.stream().map(r -> r.get("pid")).distinct().count());
                assertEquals(1,witness.stream().map(r -> r.get("xid")).distinct().count());
                assertEquals(1,witness.stream().filter(r -> r.get("t").equals("consumption_slot") && r.get("op").equals("DELETE")).count());
                assertEquals(f.f.pg.getDatabaseName(),f.f.read.initialEnvironment.get("db"));
                assertNotEquals(f.f.database,f.f.read.initialEnvironment.get("db"));
                // Legitimate duplicate-prefix representation: same content can coexist with a slot.
                var d=f.participants.get(name).definition();
                f.f.read.oracle.update("insert into consumption_slot(consumption_id,consumable_id,partition_hash,partition_id) select ?,?,h,((h::bigint%4+4)%4)::int from(select consumption_hash_v1(?,?) h)x",d.id().value(),event,d.family().name(),event);
                assertEquals(RecoveryObservation.NOT_COMPLETED,engine.observe(acquired.claim()),"effect alone never proves completion");
                f.tamper(name);
                assertEquals(RecoveryObservation.INCONSISTENT,engine.observe(acquired.claim()),participant+" divergent full business content");
                if(name==Name.POT_VERSION) {
                    f.f.read.oracle.update("update read_pot_version set payload='{}'::jsonb");
                    assertEquals(RecoveryObservation.INCONSISTENT,engine.observe(acquired.claim()),"malformed JSON is positive corruption, not SQL unavailability");
                    var expected=f.f.sourceOwner.read(c -> new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.JdbcPotVersionSource(f.f.primary).composition(event,c));
                    f.f.read.oracle.update("update read_pot_version set payload=?::jsonb",PotVersionJson.encode(expected));
                    f.f.read.oracle.execute("alter table read_subobject_version disable trigger user");
                    f.f.read.oracle.update("update read_subobject_version set payload=jsonb_set(payload,'{label}','\"\"') where kind='POT_DETAILS'");
                    assertEquals(RecoveryObservation.INCONSISTENT,engine.observe(acquired.claim()),"malformed subobject proof cannot become unavailable/satisfied");
                } else if(name==Name.POT_BALANCE) {
                    f.f.read.oracle.update("update read_pot_balance set payload='[{}]'::jsonb");
                    assertEquals(RecoveryObservation.INCONSISTENT,engine.observe(acquired.claim()),"malformed balance proof");
                }
                f.f.read.trace.add(participant+" physical PID/XID="+witness);
            }
            f.f.read.trace.add("CON-06/PRJ/A10 branch="+participant+" mode="+mode+" outcome="+result.status());
        }
        // A current mutable state can satisfy the business predicate; only L2 proves slot completion.
        if(name==Name.POT_VERSION || name==Name.POT_BALANCE || name==Name.POT_VISIBILITY || name==Name.CURRENT_BINDING)
            try(var f=new ReadParticipantsFixture(PG,participant+"-newer")) {
                UUID newer;
                if(name==Name.CURRENT_BINDING) {
                    String token=f.f.oracle.queryForObject("select binding_token from v3_eui_version where version=1",String.class);
                    var request=f.publishIdentity(new IdentityRequest.Unlink(f.eui),new BindingToken(token));
                    newer=f.f.oracle.queryForObject("select event_id from v3_identity_event where request_id=?",UUID.class,request);
                } else newer=f.mutatePot(new PotCommand.UpdatePotDetails(new Label("V2")),1);
                f.enroll(); f.execute(name,newer); var engine=f.engines.get(name);
                var old=new ClaimedSlot(f.participants.get(name).definition().id(),f.event(name),UUID.randomUUID());
                assertEquals(1,f.slots(name,old.consumableId()));
                assertEquals(RecoveryObservation.NOT_COMPLETED,engine.observe(old),"newer state never completes old present slot");
                f.f.read.oracle.update("delete from consumption_slot where consumption_id=? and consumable_id=?",old.consumptionId().value(),old.consumableId());
                assertEquals(name==Name.POT_VERSION || name==Name.POT_BALANCE?RecoveryObservation.INCONSISTENT:RecoveryObservation.COMPLETED,engine.observe(old),"exact immutable proof versus derived mutable completion");
                f.f.read.trace.add(participant+" newer state/old slot discriminant");
            }
    }

    /** Explicit bounded entry point: execute the unchanged conformance body for these three only. */
    @Test void recoveryAffectedParticipantConformance() throws Exception {
        for(var name:List.of("POT_VERSION","POT_VISIBILITY","CURRENT_BINDING")) participantConformance(name);
    }

    @Test void potVersionRecoveryRequiresCurrentPot() throws Exception {
        var name=Name.POT_VERSION;
        try(var f=new ReadParticipantsFixture(PG,"recovery-current-pot")) {
            var v2=f.mutatePot(new PotCommand.UpdatePotDetails(new Label("V2")),1);
            var v3=f.mutatePot(new PotCommand.UpdatePotDetails(new Label("V3")),2);
            f.enroll();
            for(var event:List.of(f.pot.event(),v2,v3)) f.execute(name,event);
            var original=f.f.read.oracle.queryForObject("select payload::text from read_pot_version where pot_version=3",String.class);
            f.f.read.oracle.execute("alter table read_pot_version disable trigger user");
            // All corruptions below are administrative fault injections, never production writes.
            assertAll(List.of("absent","lower","equal","higher","malformed-target","mixed-target","invalid-reference").stream().map(state -> (org.junit.jupiter.api.function.Executable) () -> {
                f.f.read.oracle.update("update read_pot_version set payload=?::jsonb where pot_version=3",original);
                f.f.read.oracle.update("delete from read_current_pot");
                if(!state.equals("absent")) {
                    long pointer=state.equals("lower")?1:state.equals("equal")?2:3;
                    f.f.read.oracle.update("insert into read_current_pot values (?,?)",f.pot.pot(),pointer);
                }
                if(state.equals("malformed-target")) f.f.read.oracle.update("update read_pot_version set payload='{}'::jsonb where pot_version=3");
                if(state.equals("mixed-target")) f.f.read.oracle.update("update read_pot_version set payload=jsonb_set(payload,'{version}','2') where pot_version=3");
                if(state.equals("invalid-reference")) {
                    assertThrows(org.springframework.dao.DataIntegrityViolationException.class,() -> f.f.read.oracle.update("update read_current_pot set pot_version=99"),"FK normally excludes a dangling pointer");
                    f.f.read.oracle.execute("alter table read_current_pot drop constraint read_current_pot_pot_id_pot_version_fkey");
                    f.f.read.oracle.update("update read_current_pot set pot_version=99");
                }
                boolean valid=state.equals("equal") || state.equals("higher");
                assertAll(state,
                    () -> { setRecoverySlot(f,name,v2,true); recoveryObservation(f,name,v2,state+"/present",valid || state.equals("absent")?RecoveryObservation.NOT_COMPLETED:RecoveryObservation.INCONSISTENT); },
                    () -> { setRecoverySlot(f,name,v2,false); recoveryObservation(f,name,v2,state+"/absent",valid?RecoveryObservation.COMPLETED:RecoveryObservation.INCONSISTENT); });
            }));
            // A coherent higher pointer cannot replace the exact historical root or subobjects.
            f.f.read.oracle.update("update read_pot_version set payload=?::jsonb where pot_version=3",original);
            f.f.read.oracle.update("update read_current_pot set pot_version=3");
            var historical=f.f.read.oracle.queryForObject("select payload::text from read_pot_version where pot_version=2",String.class);
            f.f.read.oracle.update("delete from read_pot_version where pot_version=2");
            recoveryObservation(f,name,v2,"higher/missing-historical-root",RecoveryObservation.INCONSISTENT);
            f.f.read.oracle.update("insert into read_pot_version values (?,2,1,?::jsonb)",f.pot.pot(),historical);
            f.f.read.oracle.execute("alter table read_subobject_version disable trigger user");
            f.f.read.oracle.update("delete from read_subobject_version where kind='POT_DETAILS' and local_version=2");
            recoveryObservation(f,name,v2,"higher/missing-historical-subobject",RecoveryObservation.INCONSISTENT);
        }
    }

    @Test void visibilityRecoverySupersession() throws Exception {
        var name=Name.POT_VISIBILITY;
        try(var f=new ReadParticipantsFixture(PG,"recovery-visibility-supersession")) {
            // Delete changes the visible set, so V1 and V2 are distinguishable by content.
            var v2=f.mutatePot(new PotCommand.DeletePot(),1);
            f.enroll(); f.execute(name,v2); var v1=f.event(name);
            recoveryObservation(f,name,v1,"V2-exact/V1-present",RecoveryObservation.NOT_COMPLETED);
            setRecoverySlot(f,name,v1,false); // Deliberate violation of the sole-deletion-path invariant.
            recoveryObservation(f,name,v1,"V2-exact/V1-never-executed/injected-absence",RecoveryObservation.COMPLETED);
            setRecoverySlot(f,name,v1,true);
            f.execute(name,v1); // Real obsolete-event finalization, without reverting V2.
            recoveryObservation(f,name,v1,"V2-exact/V1-terminalized",RecoveryObservation.COMPLETED);
            assertEquals(2L,f.f.read.oracle.queryForObject("select materialized_version from read_pot_visibility_state",Long.class));
            assertEquals(0,f.f.read.oracle.queryForObject("select count(*) from read_user_pot where visible",Integer.class));
            // Same durable observation without an individual V1 business receipt; derived proof.
            for(boolean slot:List.of(true,false)) {
                setRecoverySlot(f,name,v1,slot);
                recoveryObservation(f,name,v1,"V2-exact/slot="+slot,slot?RecoveryObservation.NOT_COMPLETED:RecoveryObservation.COMPLETED);
                f.f.read.oracle.update("insert into read_user_pot values (?,?,2,true)",UUID.randomUUID(),f.pot.pot());
                recoveryObservation(f,name,v1,"V2-divergent/slot="+slot,RecoveryObservation.INCONSISTENT);
                f.f.read.oracle.update("delete from read_user_pot");
            }
            checkpointDoesNotProveProcessing(f,name,v1);
        }
    }

    @Test void currentBindingRecoverySupersession() throws Exception {
        var name=Name.CURRENT_BINDING;
        try(var f=new ReadParticipantsFixture(PG,"recovery-binding-supersession")) {
            var token=f.f.oracle.queryForObject("select binding_token from v3_eui_version where version=1",String.class);
            var request=f.publishIdentity(new IdentityRequest.Unlink(f.eui),new BindingToken(token));
            // Re-registration yields an active higher version, permitting independent token/User probes.
            var r3=f.publishIdentity(new IdentityRequest.Registration(),null);
            var v2=f.f.oracle.queryForObject("select event_id from v3_identity_event where request_id=?",UUID.class,request);
            var v3=f.f.oracle.queryForObject("select event_id from v3_identity_event where subject_type='EXTERNAL_USER_IDENTITY' and request_id=?",UUID.class,r3);
            f.enroll(); f.execute(name,v2); var v1=f.event(name);
            recoveryObservation(f,name,v1,"V2-exact/V1-present",RecoveryObservation.NOT_COMPLETED);
            setRecoverySlot(f,name,v1,false); // Observationally identical; not a valid L2 history.
            recoveryObservation(f,name,v1,"V2-exact/V1-never-executed/injected-absence",RecoveryObservation.COMPLETED);
            setRecoverySlot(f,name,v1,true);
            f.execute(name,v1);
            recoveryObservation(f,name,v1,"V2-exact/V1-terminalized",RecoveryObservation.COMPLETED);
            assertEquals(2L,f.f.read.oracle.queryForObject("select eui_version from read_current_binding",Long.class));
            assertNull(f.f.read.oracle.queryForObject("select binding_token from read_current_binding",String.class));
            f.execute(name,v3);
            var exact=f.f.read.oracle.queryForMap("select * from read_current_binding");
            for(boolean slot:List.of(true,false)) {
                setRecoverySlot(f,name,v1,slot);
                recoveryObservation(f,name,v1,"V3-exact/slot="+slot,slot?RecoveryObservation.NOT_COMPLETED:RecoveryObservation.COMPLETED);
                for(var field:List.of("binding_token","user_id","eui_version")) {
                    Object corrupt=field.equals("binding_token")?"wrong-fixture-token":field.equals("user_id")?UUID.randomUUID():2L;
                    f.f.read.oracle.update("update read_current_binding set "+field+"=?",corrupt);
                    recoveryObservation(f,name,v1,"V3-divergent-"+field+"/slot="+slot,RecoveryObservation.INCONSISTENT);
                    f.f.read.oracle.update("update read_current_binding set "+field+"=?",exact.get(field));
                }
            }
            checkpointDoesNotProveProcessing(f,name,v1);
        }
    }

    void setRecoverySlot(ReadParticipantsFixture f,Name name,UUID event,boolean present) {
        var d=f.participants.get(name).definition();
        f.f.read.oracle.update("delete from consumption_slot where consumption_id=? and consumable_id=?",d.id().value(),event);
        if(present) f.f.read.oracle.update("insert into consumption_slot(consumption_id,consumable_id,partition_hash,partition_id) select ?,?,h,((h::bigint%4+4)%4)::int from(select consumption_hash_v1(?,?) h)x",d.id().value(),event,d.family().name(),event);
    }

    void recoveryObservation(ReadParticipantsFixture f,Name name,UUID event,String scenario,RecoveryObservation expected) {
        var claim=new ClaimedSlot(f.participants.get(name).definition().id(),event,UUID.randomUUID());
        var actual=f.engines.get(name).observe(claim);
        f.f.read.trace.add("RECOVERY "+name+" "+scenario+" expected="+expected+" actual="+actual);
        assertEquals(expected,actual,scenario);
    }

    void checkpointDoesNotProveProcessing(ReadParticipantsFixture f,Name name,UUID event) {
        var d=f.participants.get(name).definition();
        int partition=RoutingV1.partition(RoutingV1.hash(d.family(),event),d.partitionCount());
        // Administrative reset is deliberately invalid; it is not a legitimate Discovery regression.
        f.f.read.oracle.update("delete from discovery_checkpoint where consumption_id=? and partition_id=?",d.id().value(),partition);
        for(boolean slot:List.of(true,false)) {
            setRecoverySlot(f,name,event,slot);
            recoveryObservation(f,name,event,"checkpoint-zero/slot="+slot,RecoveryObservation.INCONSISTENT);
        }
    }

    @Test void independentProgression() throws Exception {
        var expected=Map.of(Name.POT_VERSION,"3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a",Name.POT_BALANCE,"190a93c6-b8af-5ac7-8001-72e2ec53c2df",Name.POT_VISIBILITY,"b4d04cce-b5da-5d1b-9698-5ff9af9a46fa",Name.CURRENT_BINDING,"7b8d2838-bf7a-5117-868a-e5c14b4c0221",Name.COMMAND_RESULT,"63664303-d33e-5d42-8cce-c4dae8023311",Name.IDENTITY_REQUEST_RESULT,"1d8ff246-358f-5ddd-a7f5-416efcfd7445");
        for(var disabled:Name.values()) try(var f=new ReadParticipantsFixture(PG,"disabled-"+disabled)) {
            f.enroll();
            for(var name:Name.values()) {
                var d=f.participants.get(name).definition(); assertEquals(UUID.fromString(expected.get(name)),d.id().value()); assertEquals(ConsumptionDefinition.Authority.READ,d.authority());
                assertEquals(name==Name.CURRENT_BINDING?ConsumableFamily.IDENTITY_EVENT:name==Name.COMMAND_RESULT?ConsumableFamily.COMMAND_COMPLETION_EVENT:name==Name.IDENTITY_REQUEST_RESULT?ConsumableFamily.IDENTITY_COMPLETION_EVENT:ConsumableFamily.POT_EVENT,d.family());
                if(name!=disabled) f.execute(name,f.event(name));
            }
            assertEquals(1,f.slots(disabled,f.event(disabled)));
            for(var name:Name.values()) if(name!=disabled) assertEquals(0,f.slots(name,f.event(name)),disabled+" cannot block "+name);
            assertEquals(3,f.f.read.oracle.queryForObject("select count(distinct consumption_id) from discovery_checkpoint where consumption_id in ('3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a','190a93c6-b8af-5ac7-8001-72e2ec53c2df','b4d04cce-b5da-5d1b-9698-5ff9af9a46fa')",Integer.class));
            f.f.read.trace.add("six independent consumptions; disabled="+disabled);
        }
        var orders=List.of(List.of(Name.POT_VERSION,Name.POT_BALANCE,Name.POT_VISIBILITY),List.of(Name.POT_VERSION,Name.POT_VISIBILITY,Name.POT_BALANCE),List.of(Name.POT_BALANCE,Name.POT_VERSION,Name.POT_VISIBILITY),List.of(Name.POT_BALANCE,Name.POT_VISIBILITY,Name.POT_VERSION),List.of(Name.POT_VISIBILITY,Name.POT_VERSION,Name.POT_BALANCE),List.of(Name.POT_VISIBILITY,Name.POT_BALANCE,Name.POT_VERSION));
        for(var order:orders) try(var f=new ReadParticipantsFixture(PG,"order-"+order)) {
            var v2=f.mutatePot(new PotCommand.UpdatePotDetails(new Label("V2")),1); var terminal=f.mutatePot(new PotCommand.DeletePot(),2); f.enroll();
            for(var name:order) for(var event:List.of(terminal,f.pot.event(),v2)) f.execute(name,event);
            assertEquals(3L,f.f.read.oracle.queryForObject("select pot_version from read_current_pot where pot_id=?",Long.class,f.pot.pot()));
            var versions=new JdbcPotVersionStore(f.f.read.source,f.f.read.tx); assertTrue(f.f.read.tx.<Boolean>read(c -> versions.read(f.pot.pot(),3,c).orElseThrow().deleted()));
            assertEquals(3,f.f.read.oracle.queryForObject("select count(*) from read_pot_version",Integer.class));
            var balances=new JdbcPotBalanceStore(f.f.read.source,f.f.read.tx);
            for(long v:List.of(1L,2L)) assertEquals(Map.of(),f.f.read.tx.read(c -> balances.read(f.pot.pot(),v,c).orElseThrow().values()));
            assertTrue(f.f.read.tx.read(c -> balances.read(f.pot.pot(),3,c)).isEmpty());
            var visibility=new JdbcPotVisibilityStore(f.f.read.source,f.f.read.tx); assertEquals(Set.of(),f.f.read.tx.read(c -> visibility.read(f.pot.pot(),c).orElseThrow().users()));
            for(var name:order) for(var event:List.of(terminal,f.pot.event(),v2)) assertEquals(0,f.slots(name,event));
            f.f.read.trace.add("P01/P02/P03 A11 direct branches order="+order+" with D before V1/V2");
        }
    }

    @Test void resultsBeforeDomain() throws Exception {
        try(var f=new ReadParticipantsFixture(PG,"results-before-domain")) {
            f.enroll(); f.execute(Name.COMMAND_RESULT,f.event(Name.COMMAND_RESULT)); f.execute(Name.IDENTITY_REQUEST_RESULT,f.event(Name.IDENTITY_REQUEST_RESULT));
            assertEquals(0,f.f.read.oracle.queryForObject("select count(*) from read_current_pot",Integer.class));
            assertEquals(0,f.f.read.oracle.queryForObject("select count(*) from read_current_binding",Integer.class));
            var cg=new GetCommandTerminalResult(new JdbcCommandTerminalResultStore(f.f.read.source,f.f.read.tx));
            var owner=new com.kartaguez.pocoma.domain.useridentity.ExternalIdentity("i2","actor");
            assertEquals(1L,f.f.read.tx.<Long>read(c -> cg.get(f.pot.request(),owner).orElseThrow().resultingPotVersion()));
            var ig=new GetIdentityTerminalResult(new JdbcIdentityTerminalResultStore(f.f.read.source,f.f.read.tx));
            assertEquals(1L,f.f.read.tx.<Long>read(c -> ig.get(f.identityRequest,f.eui).orElseThrow().targetVersion()));
            assertTrue(f.f.read.tx.read(c -> ig.get(f.identityRequest,owner)).isEmpty());
            f.f.read.trace.add("A10/READ results available without any domain projection");
        }
    }

    @Test void uncertainCommitHasNoSpeculativeSettlement() throws Exception {
        for(var fault:List.of(CommitAcknowledgementProxy.Fault.BEFORE_COMMIT,CommitAcknowledgementProxy.Fault.AFTER_COMMIT))
            try(var f=new ReadParticipantsFixture(PG,"unknown-"+fault);var proxy=new CommitAcknowledgementProxy(PG.getHost(),PG.getMappedPort(5432))) {
                f.enroll();
                var ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"+proxy.localPort()+"/"+PG.getDatabaseName()+"?sslmode=disable&gssEncMode=disable&preferQueryMode=simple&socketTimeout=5&currentSchema="+f.f.read.schema,f.f.read.runtimeRole,"i1-fixture");
                var tx=SubobjectPostgresFixture.owner(ds); var real=V3ReadParticipants.create(f.f.primary,f.f.sourceOwner,ds,tx).get(Name.POT_VERSION).participant();
                var p=new ConsumptionParticipant() {
                    public Optional<Prepared> prepare(UUID id,PreparationReads reads) { return real.prepare(id,reads).map(actual -> new Prepared() {
                        public void requireCompletionReady() { actual.requireCompletionReady(); }
                        public void finalizeIn(SlotCompletion completion) { actual.finalizeIn(completion); proxy.arm(fault); }
                    }); }
                    public Recovery prepareRecovery(UUID id,PreparationReads reads) { return real.prepareRecovery(id,reads); }
                };
                var engine=f.engine(Name.POT_VERSION,p,ds,tx); var handle=f.acquire(Name.POT_VERSION,engine,f.pot.event()); var result=engine.execute(handle);
                boolean committed=fault==CommitAcknowledgementProxy.Fault.AFTER_COMMIT;
                assertEquals(1,proxy.interceptedCount()); assertEquals(committed?1:0,proxy.committedReplyCount());
                assertEquals(AttemptTransactions.Resolution.UNKNOWN,result.resolution()); assertNull(result.cession()); assertNull(result.cessionResolution());
                assertEquals(committed?RecoveryObservation.COMPLETED:RecoveryObservation.NOT_COMPLETED,result.observation());
                assertEquals(committed?1:0,f.f.read.oracle.queryForObject("select count(*) from read_current_pot",Integer.class));
                assertEquals(committed?2:0,f.f.read.oracle.queryForObject("select count(*) from read_subobject_version",Integer.class));
                assertEquals(committed?1:0,f.f.read.oracle.queryForObject("select count(*) from read_pot_version",Integer.class));
                assertEquals(committed?0:1,f.slots(Name.POT_VERSION,f.pot.event())); assertEquals(1,f.capacity.snapshot().worker(),"business observation never releases uncertain capacity");
                f.f.read.trace.add("real ACK loss "+fault+" result="+result+" retained="+f.capacity.snapshot());
            }
    }

    @Test void boundedCapacityAndRenewal() throws Exception {
        try(var f=new ReadParticipantsFixture(PG,"capacity",2)) {
            f.enroll(); var entered=new CountDownLatch(2); var release=new CountDownLatch(1);
            var handles=new ArrayList<ConsumptionEngine.AcquiredClaim>(); var engines=new ArrayList<ConsumptionEngine>();
            for(var name:List.of(Name.POT_VERSION,Name.POT_BALANCE)) {
                var real=f.participants.get(name).participant();
                var p=new ConsumptionParticipant() {
                    public Optional<Prepared> prepare(UUID id,PreparationReads reads) {
                        reads.source(c -> { f.f.sql.queryForObject("select pg_backend_pid()",Integer.class); entered.countDown(); try { if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("test release barrier"); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } return null; });
                        return real.prepare(id,reads);
                    }
                    public Recovery prepareRecovery(UUID id,PreparationReads reads) { return real.prepareRecovery(id,reads); }
                };
                var engine=f.engine(name,p,f.f.read.source,f.f.read.tx); engines.add(engine); handles.add(f.acquire(name,engine,f.event(name)));
            }
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                var a=pool.submit(() -> engines.get(0).execute(handles.get(0))); var b=pool.submit(() -> engines.get(1).execute(handles.get(1)));
                try {
                    assertTrue(entered.await(10,TimeUnit.SECONDS)); assertEquals(2,f.capacity.snapshot().worker()); assertEquals(2,f.f.primary.activeConnections.get());
                    var third=f.engines.get(Name.POT_VISIBILITY); var d=f.participants.get(Name.POT_VISIBILITY).definition(); var event=f.event(Name.POT_VISIBILITY);
                    var lease=f.lease(Name.POT_VISIBILITY,third,RoutingV1.partition(RoutingV1.hash(d.family(),event),4));
                    assertTrue(third.acquire(lease,List.of(event)).claims().isEmpty(),"M_W already occupied");
                    for(int i=0;i<2;i++) {
                        var claim=handles.get(i).claim(); var before=f.f.read.oracle.queryForObject("select claimed_until from consumption_slot where consumption_id=? and consumable_id=?",java.sql.Timestamp.class,claim.consumptionId().value(),claim.consumableId());
                        var renewed=engines.get(i).renewClaims(List.of(claim)); assertEquals(1,renewed.size()); assertTrue(renewed.getFirst().claimedUntil().isAfter(before.toInstant()));
                    }
                    assertEquals(2,f.capacity.snapshot().worker(),"long physical source callbacks retain reservations");
                } finally { release.countDown(); }
                assertEquals(ConsumptionEngine.Status.COMMITTED,a.get(20,TimeUnit.SECONDS).status()); assertEquals(ConsumptionEngine.Status.COMMITTED,b.get(20,TimeUnit.SECONDS).status());
            }
            assertEquals(0,f.capacity.snapshot().worker());
            for(var name:Name.values()) assertTrue(f.capacity.supports(f.participants.get(name).definition().id()));
            f.f.read.trace.add("bounded M_W=2; six declared consumptions; renewals while two real WRITE snapshots retained; productive pool/load proof remains I8");
        }
    }
}

final class ReadParticipantsContainer {
    private ReadParticipantsContainer() {}
    static PostgreSQLContainer create() { return new PostgreSQLContainer(org.testcontainers.utility.DockerImageName.parse("postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73").asCompatibleSubstituteFor("postgres")); }
}
