package com.kartaguez.pocoma.runtime.task.v3;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.v3.BindingToken;
import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.engine.consume.registration.v3.ExecuteIdentityRequest;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.kartaguez.pocoma.runtime.consumption.v3.V3ReadParticipants.Name;
import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import javax.sql.DataSource;
import java.util.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real source producers and L2; only admission identity/consent is an internal fixture. */
final class ReadParticipantsFixture implements AutoCloseable {
    final InterAuthorityPostgresFixture f;
    final InterAuthorityPostgresFixture.Published pot;
    final UUID identityRequest;
    final ExternalIdentity eui=new ExternalIdentity("l4","owner");
    final Map<Name,V3ReadParticipants.Inert> participants;
    final WorkerCapacity capacity;
    final Map<Name,ConsumptionEngine> engines=new EnumMap<>(Name.class);
    final Map<String,PartitionLease> leases=new HashMap<>();
    final List<String> businessTables=List.of("read_subobject_version","read_pot_version","read_current_pot","read_pot_balance","read_pot_visibility_state","read_user_pot","read_current_binding","read_command_result","read_identity_request_result");

    ReadParticipantsFixture(PostgreSQLContainer pg,String scenario) throws Exception { this(pg,scenario,8); }
    ReadParticipantsFixture(PostgreSQLContainer pg,String scenario,int workerLimit) throws Exception {
        f=new InterAuthorityPostgresFixture(pg,"L4-"+scenario);
        Flyway.configure().dataSource(f.read.admin).schemas(f.read.schema).defaultSchema(f.read.schema).createSchemas(false)
            .locations("classpath:db/v3-consumption-read/migration","classpath:db/v3-read-business/migration")
            .placeholders(Map.of("expectedDatabase",pg.getDatabaseName(),"expectedSchema",f.read.schema,"expectedAdmin",f.read.adminRole,"expectedRuntime",f.read.runtimeRole)).load().migrate();
        f.read.script("grant-l2-discovery.sql",Map.of("runtime_role",f.read.runtimeRole,"reactivation_role",f.read.reactivationRole));
        pot=f.publish(); identityRequest=publishIdentity(new IdentityRequest.Registration(),null);
        // Remove only the I2 fixture's synthetic initial READ enrollment, before any READ effect.
        // Production enrollment below is exclusively the real DiscoveryEngine.
        f.read.oracle.update("delete from consumption_slot"); f.read.oracle.update("delete from discovery_checkpoint");
        participants=V3ReadParticipants.create(f.primary,f.sourceOwner,f.read.source,f.read.tx);
        var limits=new HashMap<ConsumptionId,Integer>(); participants.values().forEach(p -> limits.put(p.definition().id(),8));
        capacity=new WorkerCapacity(workerLimit,limits);
        for(var name:Name.values()) engines.put(name,engine(name,participants.get(name).participant(),f.read.source,f.read.tx));
    }
    ConsumptionEngine engine(Name name,ConsumptionParticipant participant,DataSource read,AttemptTransactions effects) {
        var d=participants.get(name).definition();
        return new ConsumptionEngine(d,effects,new JdbcConsumptionSlotStore(read),participant,f.sourceOwner,
            Optional.of(new JdbcEventDiscoveryReferences(f.primary,d.family(),SubobjectPostgresFixture.discoverySettings())),
            r -> V3ConsumptionDiscoveryComposition.relevant(d,r),new JdbcDiscoveryAdmissionStore(read,SubobjectPostgresFixture.discoverySettings()),
            f.read.tx,f.read.slots,f.read.ownership,f.read.settings,capacity,
            failure -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,null,ConsumptionObservation.noop());
    }
    PartitionLease lease(Name name,ConsumptionEngine engine,int partition) {
        return leases.computeIfAbsent(name+":"+partition,k -> engine.acquirePartition(partition).orElseThrow());
    }
    void enroll() {
        for(var name:Name.values()) {
            var d=participants.get(name).definition(); var engine=engines.get(name);
            var discovery=new DiscoveryEngine(d,f.read.tx,f.read.ownership,f.admissions,SubobjectPostgresFixture.discoverySettings(),f.sourceOwner,
                new JdbcEventDiscoveryReferences(f.primary,d.family(),SubobjectPostgresFixture.discoverySettings()),r -> V3ConsumptionDiscoveryComposition.relevant(d,r));
            for(int p=0;p<4;p++) discovery.discoverEvents(lease(name,engine,p));
        }
    }
    UUID event(Name name) {
        return switch(name) {
            case POT_VERSION,POT_BALANCE,POT_VISIBILITY -> pot.event();
            case COMMAND_RESULT -> pot.completion();
            case CURRENT_BINDING -> f.oracle.queryForObject("select event_id from v3_identity_event where subject_type='EXTERNAL_USER_IDENTITY' and request_id=?",UUID.class,identityRequest);
            case IDENTITY_REQUEST_RESULT -> f.oracle.queryForObject("select event_id from v3_identity_completion_event where subject_id=?",UUID.class,identityRequest);
        };
    }
    ConsumptionEngine.AcquiredClaim acquire(Name name,ConsumptionEngine engine,UUID event) {
        var d=participants.get(name).definition(); int p=RoutingV1.partition(RoutingV1.hash(d.family(),event),4);
        var claims=engine.acquire(lease(name,engine,p),List.of(event)).claims(); assertEquals(1,claims.size(),name+" acquire "+event); return claims.getFirst();
    }
    ConsumptionEngine.Attempt execute(Name name,UUID event) {
        var e=engines.get(name); var result=e.execute(acquire(name,e,event));
        assertEquals(ConsumptionEngine.Status.COMMITTED,result.status(),() -> name+" "+result+" cause="+result.cause());
        return result;
    }
    int slots(Name name,UUID event) {
        return f.read.oracle.queryForObject("select count(*) from consumption_slot where consumption_id=? and consumable_id=?",Integer.class,participants.get(name).definition().id().value(),event);
    }
    Map<String,Long> vector() {
        var v=new LinkedHashMap<String,Long>(); for(var table:businessTables) v.put(table,f.read.oracle.queryForObject("select count(*) from "+table,Long.class)); return v;
    }
    UUID mutatePot(PotCommand.Intent intent,long observed) {
        var id=UUID.randomUUID(); var request=new PotCommand(id,new PotCommand.Eui("i2","actor"),"fixture",new PotId(pot.pot()),observed,intent);
        f.oracle.update("insert into v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload) values (?,'i2','actor','fixture',?,?,?,?::jsonb)",id,pot.pot(),observed,intent.getClass().getSimpleName(),PotCommandJson.encode(intent));
        var d=new ConsumptionDefinition(new ConsumptionId(UUID.fromString("98f7268d-d340-581c-b83d-010926223e14")),ConsumableFamily.COMMAND,ConsumptionDefinition.Authority.WRITE);
        seedWrite(d,id);
        var authority=new PotActorAuthority() {
            public Witness observe(PotCommand c) { return new Witness(1,Optional.of(new UserId(new UUID(0,71)))); }
            public boolean protect(PotCommand c,Witness w) { return true; }
        };
        var participant=new ExecutePotCommand(new JdbcPotCommandStore(f.primary,f.sourceOwner),new JdbcPotCommandObservation(f.primary),authority,new JdbcPotHistoryAdapter(f.primary),new JdbcPotRevisionAdapter(f.primary),Clock.systemUTC());
        executeWrite(d,id,participant);
        return f.oracle.queryForObject("select event_id from v3_pot_event where request_id=?",UUID.class,id);
    }
    UUID publishIdentity(IdentityRequest.Operation operation,BindingToken presented) {
        var id=UUID.randomUUID(); var target=operation instanceof IdentityRequest.Link link?link.target():operation instanceof IdentityRequest.Unlink unlink?unlink.target():eui;
        var request=new IdentityRequest(id,eui,operation,presented,Instant.EPOCH,new IdentityRequest.AdmissionContext("L4 internal fixture","1",eui,target,id.toString(),id.toString(),Instant.EPOCH,Map.of(),IdentityRequest.Origin.CONTROLLED_INTERNAL));
        f.oracle.update("insert into v3_identity_request(request_id,requester_issuer,requester_subject,operation,binding_token,target_issuer,target_subject,admission_context,created_at) values (?,?,?,?,?,?,?,?::jsonb,?)",id,eui.issuer(),eui.subject(),IdentityRequestJson.operation(request),presented==null?null:presented.value(),target.issuer(),target.subject(),IdentityRequestJson.encode(request.admission()),java.sql.Timestamp.from(Instant.EPOCH));
        var d=new ConsumptionDefinition(new ConsumptionId(UUID.fromString("2999e75c-272d-5afe-a966-c9364ef021fe")),ConsumableFamily.IDENTITY_REQUEST,ConsumptionDefinition.Authority.WRITE);
        seedWrite(d,id);
        var p=new ExecuteIdentityRequest(new JdbcIdentityRequestStore(f.primary,f.sourceOwner),new JdbcIdentityRequestObservation(f.primary),new JdbcIdentityAuthority(f.primary,f.sourceOwner),() -> new BindingToken("fixture-"+UUID.randomUUID()),UUID::randomUUID);
        executeWrite(d,id,p); return id;
    }
    void seedWrite(ConsumptionDefinition d,UUID id) {
        f.oracle.update("insert into consumption_slot(consumption_id,consumable_id,partition_hash,partition_id) select ?,?,h,((h::bigint%4+4)%4)::int from(select consumption_hash_v1(?,?) h)x",d.id().value(),id,d.family().name(),id);
    }
    void executeWrite(ConsumptionDefinition d,UUID id,ConsumptionParticipant participant) {
        var e=new ConsumptionEngine(d,f.sourceOwner,new JdbcConsumptionSlotStore(f.primary),participant,new JdbcPartitionOwnershipStore(f.primary),f.read.settings,new WorkerCapacity(1,Map.of(d.id(),1)),fault -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
        int p=RoutingV1.partition(RoutingV1.hash(d.family(),id),4); var lease=f.primaryLease(e,d,p);
        var result=e.execute(e.acquire(lease,List.of(id)).claims().getFirst()); assertEquals(ConsumptionEngine.Status.COMMITTED,result.status(),() -> String.valueOf(result.cause()));
    }
    void witnesses() {
        f.read.oracle.execute("create table l4_witness(t text,op text,pid integer,xid text)");
        f.read.oracle.execute("grant insert,select on l4_witness to "+f.read.runtimeRole);
        f.read.oracle.execute("create function l4_witness() returns trigger language plpgsql as $$ begin insert into l4_witness values(tg_table_name,tg_op,pg_backend_pid(),pg_current_xact_id()::text);return null;end $$");
        for(var table:businessTables) f.read.oracle.execute("create trigger l4_witness after insert or update on "+table+" for each row execute function l4_witness()");
        f.read.oracle.execute("create trigger l4_witness after delete on consumption_slot for each row execute function l4_witness()");
    }
    void tamper(Name name) {
        switch(name) {
            case POT_VERSION -> { f.read.oracle.execute("alter table read_pot_version disable trigger user"); f.read.oracle.update("update read_pot_version set payload=jsonb_set(payload,'{deleted}','true')"); }
            case POT_BALANCE -> { f.read.oracle.execute("alter table read_pot_balance disable trigger user"); f.read.oracle.update("update read_pot_balance set payload=?::jsonb","[{\"id\":\"00000000-0000-0000-0000-000000000099\",\"n\":\"0\",\"d\":\"1\"}]"); }
            case POT_VISIBILITY -> f.read.oracle.update("insert into read_user_pot values (?,?,1,true)",UUID.randomUUID(),pot.pot());
            case CURRENT_BINDING -> f.read.oracle.update("update read_current_binding set binding_token=?","different-fixture-token");
            case COMMAND_RESULT -> { f.read.oracle.execute("alter table read_command_result disable trigger user"); f.read.oracle.update("update read_command_result set resulting_pot_version=9"); }
            case IDENTITY_REQUEST_RESULT -> { f.read.oracle.execute("alter table read_identity_request_result disable trigger user"); f.read.oracle.update("update read_identity_request_result set target_version=9"); }
        }
    }
    public void close() throws Exception { f.close(); }
}
