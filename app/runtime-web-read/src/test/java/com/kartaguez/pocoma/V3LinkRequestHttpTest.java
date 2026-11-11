package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.*;
import static com.kartaguez.pocoma.V3ReadHttpFixture.*;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpResponse;
import java.util.*;

@Testcontainers
class V3LinkRequestHttpTest {
    @Container static final PostgreSQLContainer PG=container();
    V3ReadHttpFixture f;
    @BeforeEach void setup()throws Exception{f=new V3ReadHttpFixture(PG);f.enableAdmission();}
    @AfterEach void close(){if(f!=null)f.close();}
    void start(){f.start(PocomaWebReadApplication.class);}
    String body(){return "{\"bindingToken\":\"E1-SECRET\",\"target\":{\"issuer\":\""+f.issuer+"\",\"subject\":\"E2\"}}";}
    HttpResponse<byte[]> call(String method,String path,String actor,String body,Map<String,String> headers)throws Exception{
        return f.request(method,path,actor==null?null:f.jwt(actor),null,body==null?null:body.getBytes(StandardCharsets.UTF_8),headers);
    }
    HttpResponse<byte[]> create(String key,String body)throws Exception{return call("POST","/identity/link-requests","E1",body,Map.of("Content-Type","application/json","Idempotency-Key",key));}
    String create()throws Exception{var r=create("key",body());assertEquals(201,r.statusCode(),new String(r.body(),StandardCharsets.UTF_8));return f.body(r).get("linkRequestId").asText();}
    String route(String id){return "/identity/link-requests/"+id;}
    @Test void jwtValidationAndTargetMasking()throws Exception{
        start();var id=create();var now=Instant.now();
        var forged=new RSAKeyGenerator(2048).generate();
        var tokens=new ArrayList<String>();tokens.add(null);tokens.add("invalid");
        tokens.add(f.jwt("E2","https://wrong.invalid","pocoma-i1",now.minusSeconds(2),now.plusSeconds(60),now.minusSeconds(3),f.key,JWSAlgorithm.RS256));
        tokens.add(f.jwt("E2",f.issuer,"wrong-audience",now.minusSeconds(2),now.plusSeconds(60),now.minusSeconds(3),f.key,JWSAlgorithm.RS256));
        tokens.add(f.jwt("E2",f.issuer,"pocoma-i1",now.minusSeconds(2),now.plusSeconds(60),now.minusSeconds(3),forged,JWSAlgorithm.RS256));
        tokens.add(f.jwt("E2",f.issuer,"pocoma-i1",now.minusSeconds(60),now.minusSeconds(1),now.minusSeconds(61),f.key,JWSAlgorithm.RS256));
        for(String token:tokens)for(String method:List.of("GET","POST")){
            var r=f.request(method,route(id)+(method.equals("POST")?"/accept":""),token,null,null,Map.of());
            assertEquals(401,r.statusCode());assertEquals("no-store",r.headers().firstValue("Cache-Control").orElseThrow());
        }
        for(String actor:List.of("E1","E3"))assertEquals(404,call("POST",route(id)+"/accept",actor,null,Map.of()).statusCode());
        assertEquals(404,call("GET",route(id),"E3",null,Map.of()).statusCode());
        assertEquals(404,call("GET",route(UUID.randomUUID().toString()),"E3",null,Map.of()).statusCode());
        assertEquals(0,f.admissionObserver.queryForObject("select count(*) from v3_identity_request",Integer.class));
    }
    @Test void missingExpiredAndForbiddenProposal()throws Exception{
        f.properties.put("pocoma.v3.http.admission.link-proposal-ttl-seconds","1");start();var id=create();
        assertEquals(400,create("unknown",body().replace("\"target\":","\"userId\":\"forged\",\"target\":")).statusCode());
        assertEquals(400,call("POST",route(id)+"/accept","E2","{}",Map.of("Content-Type","application/json")).statusCode());
        assertEquals(400,call("POST",route(id)+"/accept","E2",null,Map.of("Idempotency-Key","forged")).statusCode());
        assertEquals(400,call("GET",route("bad-id"),"E2",null,Map.of()).statusCode());
        assertEquals(400,call("GET",route(id)+"?target=E3","E2",null,Map.of()).statusCode());
        long end=System.nanoTime()+Duration.ofSeconds(3).toNanos();
        while(!f.admissionObserver.queryForObject("select clock_timestamp()>=expires_at from v3_link_proposal",Boolean.class)&&System.nanoTime()<end)Thread.sleep(5);
        assertEquals(410,call("GET",route(id),"E1",null,Map.of()).statusCode());
        assertEquals(410,call("POST",route(id)+"/accept","E2",null,Map.of()).statusCode());
        assertEquals(404,call("GET",route(id),"E3",null,Map.of()).statusCode());
        assertEquals(0,f.admissionObserver.queryForObject("select count(*) from v3_identity_request",Integer.class));
    }
    @Test void summaryAndAcceptedOwnership()throws Exception{
        start();var id=create();
        var r=call("GET",route(id),"E2",null,Map.of());assertEquals(200,r.statusCode());
        var summary=f.body(r);assertEquals("E1",summary.get("initiator").get("subject").asText());
        assertEquals("LINK_TO_INITIATOR_ACCOUNT",summary.get("action").asText());assertTrue(summary.get("potCount").isNull());
        assertEquals(Set.of("linkRequestId","initiator","target","action","createdAt","expiresAt","state","requestId","potCount","potCountAsOf"),
            new HashSet<>(java.util.stream.StreamSupport.stream(((Iterable<String>)()->summary.fieldNames()).spliterator(),false).toList()));
        var accepted=call("POST",route(id)+"/accept","E2",null,Map.of());assertEquals(202,accepted.statusCode());
        assertEquals(id,f.body(accepted).get("requestId").asText());assertEquals("/api/v3"+route(id),accepted.headers().firstValue("Location").orElseThrow());
        assertEquals(f.body(accepted),f.body(call("POST",route(id)+"/accept","E2",null,Map.of())));
        assertEquals("E1",f.admissionObserver.queryForObject("select requester_subject from v3_identity_request",String.class));
        assertFalse(new String(r.body(),StandardCharsets.UTF_8).contains("SECRET"));
        assertEquals(404,call("GET","/identity/requests/"+id+"/result","E2",null,Map.of()).statusCode());
        assertEquals(400,call("GET","/me/binding?subject=E1","E2",null,Map.of()).statusCode());
    }
    @Test void boundedTransportAndNoSynchronousLink()throws Exception{
        start();
        assertEquals(413,create("large"," ".repeat(16385)).statusCode());
        assertEquals(400,create("duplicate",body().replace("\"target\":","\"bindingToken\":\"different\",\"target\":")).statusCode());
        // EXCLUSIVE blocks business row locks/writes while allowing PostgreSQL type metadata.
        // The admission role has no SELECT privilege on the business authority itself.
        try(var connection=f.admissionObserver.getDataSource().getConnection()){
            connection.setAutoCommit(false);connection.createStatement().execute("lock table v3_eui_authority in exclusive mode");
            var id=create();var replay=create("key",body());assertEquals(id,f.body(replay).get("linkRequestId").asText());
            assertEquals(409,create("key",body().replace("E1-SECRET","DIFFERENT")).statusCode());
            assertEquals(202,call("POST",route(id)+"/accept","E2",null,Map.of()).statusCode());connection.rollback();
        }
        for(String table:List.of("consumption_slot","v3_eui_authority","v3_eui_version","v3_identity_event"))
            assertEquals(0,f.admissionObserver.queryForObject("select count(*) from "+table,Integer.class),table);
        assertEquals(400,call("POST","/identity/requests","E1","{\"operation\":\"Link\"}",Map.of("Content-Type","application/json","Idempotency-Key","direct")).statusCode());
    }
}
