package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real Keycloak JWTs, public HTTP, delivered Worker and projected READ; no result injection. */
@Testcontainers
class V3LinkBrunoPostgresTest {
    @Container static final PostgreSQLContainer PG=V3AdmissionFlowPostgresTest.PG;
    @BeforeAll static void initialize(){V3AdmissionFlowPostgresTest.initialize();}
    V3AdmissionFlowPostgresTest f;
    TestInfo testInfo;
    @BeforeEach void remember(TestInfo info){testInfo=info;}
    @AfterEach void close()throws Exception{if(f!=null)f.close();}
    @Test void realKeycloakLinkProtocol()throws Exception{
        var repo=Path.of("../..").toAbsolutePath().normalize();
        var output=Path.of(System.getProperty("pocoma.verification.reports","target/reports")).getParent().resolve("bruno-i3");
        Files.createDirectories(output);var authFile=output.resolve("ephemeral-auth.json");
        var json=new ObjectMapper();var inputs=V3AdmissionFlowPostgresTest.brunoInputs(repo);
        var auth=new ProcessBuilder("python3","-B",repo.resolve("scripts/testing/l5-i3-auth.py").toString());
        auth.environment().put("POCOMA_I3_AUTH_OUTPUT",authFile.toString());
        var authenticating=auth.redirectErrorStream(true).redirectOutput(output.resolve("auth.log").toFile()).start();
        if(!authenticating.waitFor(60,TimeUnit.SECONDS)){authenticating.destroyForcibly();authenticating.waitFor();fail("Real Keycloak authentication deadline; no external PASS");}
        assertEquals(0,authenticating.exitValue(),"Real Keycloak authentication failed; inspect sanitized auth.log");
        try{
            var actors=json.readTree(Files.readString(authFile));var identities=new HashSet<String>();
            var metadata=new LinkedHashMap<String,Object>();
            for(String name:List.of("E1","E2","E3")){
                var actor=actors.get(name);identities.add(actor.get("issuer").asText()+"/"+actor.get("subject").asText());
                var copy=actor.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)copy).remove("token");metadata.put(name,copy);
            }
            assertEquals(3,identities.size());Files.writeString(output.resolve("real-jwt-claims.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
            f=new V3AdmissionFlowPostgresTest();f.setup(testInfo);
            String issuer=actors.get("E1").get("issuer").asText();
            f.http.properties.put("pocoma.v3.http.issuer",issuer);
            f.http.properties.put("pocoma.v3.http.audience","pocoma-api");
            f.http.properties.put("pocoma.v3.http.jwk-set-uri",issuer+"/protocol/openid-connect/certs");
            f.start();
            var command=List.of(repo.resolve("scripts/bruno-v3/node_modules/.bin/bru").toString(),"run","-r","03 Link Requests",
                "--env","Local","--sandbox","developer","--noproxy","--reporter-json",output.resolve("bruno.json").toString(),"--reporter-skip-all-headers","--reporter-skip-body");
            var process=new ProcessBuilder(command).directory(repo.resolve("scripts/bruno-v3").toFile());var env=process.environment();
            // Override inherited fixture variables so the selected corpus uses the actual IdP.
            for(String key:new ArrayList<>(env.keySet()))if(key.startsWith("POCOMA_I1_")||key.startsWith("POCOMA_I2_"))env.remove(key);
            env.put("POCOMA_I2_BASE_URL",f.http.baseUrl);env.put("POCOMA_I2_bearerOwner",actors.get("E1").get("token").asText());
            env.put("POCOMA_I3_AUTH_FILE",authFile.toString());env.put("POCOMA_I3_HELPER",repo.resolve("scripts/bruno-v3/lib/i3-link.js").toString());
            env.put("POCOMA_I3_RECEIPTS",output.resolve("requests.jsonl").toString());
            long start=System.nanoTime();var running=process.redirectErrorStream(true).redirectOutput(output.resolve("bruno.log").toFile()).start();
            boolean finished=running.waitFor(120,TimeUnit.SECONDS);
            if(!finished){running.destroyForcibly();running.waitFor();}
            double seconds=(System.nanoTime()-start)/1e9;
            assertTrue(finished,"Bruno bounded deadline");assertEquals(0,running.exitValue(),"Bruno failed: see retained output");
            var scenarios=new ArrayList<String>();int requests=0;
            for(String line:Files.readAllLines(output.resolve("requests.jsonl"))){var r=json.readTree(line);assertEquals("PASS",r.get("verdict").asText());scenarios.add(r.get("scenario").asText());requests+=r.get("requests").size();}
            assertEquals(List.of("B301","B302","B303","B304","B305","B307","B308","B309","B310","B313"),scenarios);
            // Independent SQL observations: a single physical admission/effect for Link, requester E1.
            var sql=f.worker.write;
            var unit=sql.queryForMap("select (select count(*) from v3_link_proposal) proposals,"
                +"(select count(*) from v3_identity_request where operation='LINK') links,"
                +"(select count(*) from v3_admission_association where operation='Link') associations,"
                +"(select count(*) from v3_identity_request_result where outcome='CHANGED') changed,"
                +"(select count(*) from v3_eui_version) versions");
            assertEquals(1L,unit.get("proposals"));assertEquals(1L,unit.get("links"));assertEquals(1L,unit.get("associations"));assertEquals(2L,unit.get("changed"));assertEquals(2L,unit.get("versions"));
            assertEquals(actors.get("E1").get("subject").asText(),sql.queryForObject("select requester_subject from v3_identity_request where operation='LINK'",String.class));
            var report=json.readTree(Files.readString(output.resolve("bruno.json")));assertFalse(report.isEmpty());
            Files.writeString(output.resolve("receipt.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scenarios",scenarios,"helperRequests",requests,"primaryRequests",10,"brunoSeconds",seconds,"independentSql",unit,"issuer",issuer,"realIdp",true)));
            assertEquals(inputs,V3AdmissionFlowPostgresTest.brunoInputs(repo),"Bruno executable inputs remained unchanged");
            for(var actor:actors)assertFalse(Files.readString(output.resolve("bruno.log")).contains(actor.get("token").asText()),"JWT leaked in Bruno log");
        }finally{Files.deleteIfExists(authFile);}
    }
}
