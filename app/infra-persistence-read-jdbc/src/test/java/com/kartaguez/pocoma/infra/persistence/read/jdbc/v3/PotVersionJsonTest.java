package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3;
import com.kartaguez.pocoma.domain.projection.v3.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
class PotVersionJsonTest {
    @Test void strictCanonicalCodec() throws Exception {
        var v=new PotVersion(UUID.randomUUID(),2,1,2,false,List.of(new PotVersion.Shareholder(UUID.randomUUID(),1,1,1,false,true)),List.of(),Set.of(UUID.randomUUID()),Set.of());
        String json=PotVersionJson.encode(v);
        assertEquals(v,new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,PotVersion.class));
        assertEquals(v,PotVersionJson.decode(1,json));
        for(String bad:List.of(json.replace("\"version\":2","\"version\":0"),json.replace("\"deleted\":false","\"deleted\":null"),json.replace("\"version\":2","\"version\":\"2\""),json.substring(0,json.length()-1)+",\"projectedAt\":0}",json+"{}"))
            assertThrows(IllegalStateException.class,() -> PotVersionJson.decode(1,bad),bad);
        assertThrows(IllegalStateException.class,() -> PotVersionJson.decode(2,json));
        assertNotEquals(v,PotVersionJson.decode(1,json.replace("\"version\":2","\"version\":3")));
    }
}
