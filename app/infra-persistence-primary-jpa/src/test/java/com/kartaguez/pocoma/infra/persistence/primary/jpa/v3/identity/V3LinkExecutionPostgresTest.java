package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.HmacAdmissionProtection;
import com.kartaguez.pocoma.contract.writeadmission.LinkProposalInput;

import static org.junit.jupiter.api.Assertions.*;
import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionProtection;
import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.engine.admit.registration.v3.*;
import com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import java.time.*;
import java.util.*;
import java.nio.file.Path;

/** New HTTP consent provenance through the unchanged physical L2/L3 participant. */
@Testcontainers
class V3LinkExecutionPostgresTest extends IdentityEngineFixture {
    AdmitLinkProposal links;
    @Override String[] migrationLocations() {
        var locations = new ArrayList<>(Arrays.asList(super.migrationLocations()));
        locations.add("classpath:db/v3-admission/migration");
        return locations.toArray(String[]::new);
    }
    @BeforeEach void admission() throws Exception {
        Flyway.configure().dataSource(adminSource).defaultSchema(schema).schemas(schema)
            .locations("classpath:db/v3-pot/migration","classpath:db/v3-pot-execution/migration",
                "classpath:db/v3-consumption-write/migration","classpath:db/v3-identity/migration","classpath:db/v3-admission/migration")
            .placeholders(Map.of("expectedDatabase",PG.getDatabaseName(),"expectedSchema",schema,"expectedAdmin",PG.getUsername(),"expectedRuntime",schema+"_runtime"))
            .load().migrate();
        String role=schema+"_admission";admin.execute("create role "+role+" login password 'ephemeral-i3'");
        String name="grant-l5-admission.sql", destination="/tmp/"+schema+".sql";
        PG.copyFileToContainer(MountableFile.forHostPath(Path.of("../../scripts/v3/"+name).toAbsolutePath()),destination);
        var applied=PG.execInContainer("env","PGOPTIONS=-c search_path="+schema,"psql","-X","-U",PG.getUsername(),"-d",PG.getDatabaseName(),
            "-v","ON_ERROR_STOP=1","-v","expected_database="+PG.getDatabaseName(),"-v","expected_schema="+schema,
            "-v","expected_admin="+PG.getUsername(),"-v","admission_role="+role,"-f",destination);
        assertEquals(0,applied.getExitCode(),applied.getStderr());
        var source=new DriverManagerDataSource(PG.getJdbcUrl()+"&currentSchema="+schema+",public",role,"ephemeral-i3");
        var manager=new DataSourceTransactionManager(source);manager.setNestedTransactionAllowed(false);
        var owner=new SpringAttemptTransactions(source,manager,5);
        AdmissionSqlAuthority.verify(source,PG.getDatabaseName(),schema,role);
        links=new AdmitLinkProposal(new JdbcAdmissionStore(source,owner),new AdmissionSqlTransactions(source,owner),
            new HmacAdmissionProtection("lookup",new byte[32],"key",Map.of("key",new byte[32])),Clock.systemUTC(),Duration.ofMinutes(10),4096);
    }
    AuthenticatedExternalPrincipal actor(ExternalIdentity e) {
        var now=Instant.now();return new AuthenticatedExternalPrincipal(e.issuer(),e.subject(),now.minusSeconds(2),now.minusSeconds(1),now.plusSeconds(300),Set.of());
    }
    UUID proposal(ExternalIdentity e1, ExternalIdentity e2, BindingToken token) {
        return links.create(actor(e1),UUID.randomUUID().toString(),LinkProposalInput.decode(Map.of("bindingToken",token.value(),
            "target",Map.of("issuer",e2.issuer(),"subject",e2.subject())),e1)).linkRequestId();
    }
    IdentityRequest admitted(UUID id) { return tx.read(c->store.load(id).orElseThrow().request()); }
    void process(UUID id,String outcome) {
        var result=execute(admitted(id));assertEquals(ConsumptionProtocol.Status.COMMITTED,result.status(),()->String.valueOf(result.cause()));
        assertEquals(outcome,admin.queryForObject("select outcome from v3_identity_request_result where request_id=?",String.class,id));
    }
    @Test void acceptedLinkReplayAfterTerminalAndUnlink() {
        var e1=key("E1");var e2=key("E2");executeAdmitted(request(new IdentityRequest.Registration(),e1,null,e1));
        var token=current(e1).bindingToken();var id=proposal(e1,e2,token);
        assertEquals(1,count("v3_identity_request"));assertEquals(0,count("consumption_slot"));
        links.accept(actor(e2),id);assertEquals(e1,admitted(id).requesterEui());assertEquals(1,count("v3_eui_version"));
        process(id,"CHANGED");assertEquals(current(e1).userId(),current(e2).userId());
        var first=current(e2).bindingToken();executeAdmitted(request(new IdentityRequest.Unlink(e2),e1,token,e2));
        assertNull(current(e2).userId());var before=vector();
        assertEquals(id,links.accept(actor(e2),id).requestId());assertEquals(before,vector());assertNotEquals(first,current(e2).bindingToken());
    }
    @Test void staleInitiatorAtCreateAfterCreateAndAfterAdmission() {
        for(String phase:List.of("before-create","after-create","after-admission")) {
            var e1=key("E1-"+phase);var e2=key("E2-"+phase);
            executeAdmitted(request(new IdentityRequest.Registration(),e1,null,e1));var old=current(e1).bindingToken();
            UUID id=null;
            if(!phase.equals("before-create"))id=proposal(e1,e2,old);
            if(phase.equals("after-admission"))links.accept(actor(e2),id);
            executeAdmitted(request(new IdentityRequest.Unlink(e1),e1,old,e1));
            // Re-registration obtains a new occurrence; it cannot rehabilitate the captured token.
            executeAdmitted(request(new IdentityRequest.Registration(),e1,null,e1));assertNotEquals(old,current(e1).bindingToken());
            if(id==null)id=proposal(e1,e2,old);
            if(!phase.equals("after-admission"))links.accept(actor(e2),id);
            process(id,"REJECTED");assertEquals(0L,admin.queryForObject("select count(*) from v3_eui_version v join v3_eui_authority a using(eui_id) where a.issuer=? and a.subject=?",Long.class,e2.issuer(),e2.subject()));
            assertEquals("INVALID_BINDING",admin.queryForObject("select rejection from v3_identity_request_result where request_id=?",String.class,id));
        }
    }
    @Test void targetBecomesBoundBeforeExecution() {
        var e1=key("E1");var e2=key("E2");executeAdmitted(request(new IdentityRequest.Registration(),e1,null,e1));
        var id=proposal(e1,e2,current(e1).bindingToken());links.accept(actor(e2),id);
        executeAdmitted(request(new IdentityRequest.Registration(),e2,null,e2));var winner=current(e2);
        process(id,"REJECTED");assertEquals(winner,current(e2));
    }
    @Test void distinctProposalsSameTargetAdmitOnlyOneMutation() {
        var e1=key("E1");var e2=key("E2");executeAdmitted(request(new IdentityRequest.Registration(),e1,null,e1));
        var a=proposal(e1,e2,current(e1).bindingToken());var b=proposal(e1,e2,current(e1).bindingToken());
        links.accept(actor(e2),a);links.accept(actor(e2),b);process(a,"CHANGED");var winner=current(e2);
        process(b,"REJECTED");assertEquals(winner,current(e2));assertEquals(1,winner.version());
    }
}
