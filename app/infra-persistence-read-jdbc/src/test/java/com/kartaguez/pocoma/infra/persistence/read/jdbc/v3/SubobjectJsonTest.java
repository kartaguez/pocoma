package com.kartaguez.pocoma.infra.persistence.read.jdbc.v3;

import static org.junit.jupiter.api.Assertions.*;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.domain.projection.v3.SubobjectContent.*;
import com.kartaguez.pocoma.port.projection.v3.CorruptSubobjectException;
import java.util.*;
import org.junit.jupiter.api.Test;

class SubobjectJsonTest {
    final UUID p = new UUID(0, 1), o = new UUID(0, 2);
    final String id = "00000000-0000-0000-0000-000000000002";
    SubobjectKey key(SubobjectKind k) { return new SubobjectKey(p, k, k.rank() < 2 ? p : o, 1); }
    Map<SubobjectKind, String> fixtures() {
        return Map.of(SubobjectKind.POT_HEADER, "{\"deleted\":false}",
                SubobjectKind.POT_DETAILS, "{\"label\":\"  Café é\",\"creatorId\":\""+id+"\"}",
                SubobjectKind.SHAREHOLDER_HEADER, "{\"deleted\":true}",
                SubobjectKind.SHAREHOLDER_DETAILS, "{\"name\":\"\",\"userId\":null}",
                SubobjectKind.SHAREHOLDER_WEIGHT, "{\"defaultWeight\":{\"n\":\"1234567890123456789012345678901\",\"d\":\"2\"}}",
                SubobjectKind.EXPENSE_HEADER, "{\"deleted\":false}",
                SubobjectKind.EXPENSE_DETAILS, "{\"label\":\"E\",\"date\":\"+999999999-12-31\"}",
                SubobjectKind.EXPENSE_FINANCIALS, "{\"amount\":{\"n\":\"0\",\"d\":\"1\"},\"payerId\":\""+id+"\",\"participants\":[{\"shareholderId\":\""+id+"\",\"specificWeight\":null}]}");
    }
    void rejects(SubobjectKind k, String json) {
        assertThrows(CorruptSubobjectException.class, () -> SubobjectJson.decode(key(k), 1, json), json);
    }
    @Test void strictCodec() {
        fixtures().forEach((k, json) -> {
            var value = SubobjectJson.decode(key(k), 1, json);
            assertEquals(k, value.content().kind());
            assertEquals(value, SubobjectJson.decode(key(k), 1, SubobjectJson.encode(value)));
            assertThrows(CorruptSubobjectException.class, () -> SubobjectJson.decode(key(k), 2, json));
            rejects(k, "{}"); rejects(k, "null"); rejects(k, "[]");
            rejects(k, json.substring(0, json.length()-1)+",\"extra\":true}");
            rejects(k, json+" {}");
        });
        var pd = (PotDetailsContent) SubobjectJson.decode(key(SubobjectKind.POT_DETAILS), 1, fixtures().get(SubobjectKind.POT_DETAILS)).content();
        assertEquals("  Café é", pd.value().label().value());
        assertEquals(o, pd.value().creatorId().value());
        var w = (ShareholderWeightContent) SubobjectJson.decode(key(SubobjectKind.SHAREHOLDER_WEIGHT), 1, fixtures().get(SubobjectKind.SHAREHOLDER_WEIGHT)).content();
        assertEquals(new java.math.BigInteger("1234567890123456789012345678901"), w.value().defaultWeight().value().numerator());
        assertEquals(java.math.BigInteger.TWO, w.value().defaultWeight().value().denominator());
        assertEquals(SubobjectJson.decode(key(SubobjectKind.POT_DETAILS), 1, "{\"creatorId\":\""+id+"\",\"label\":\"\"}"),
                SubobjectJson.decode(key(SubobjectKind.POT_DETAILS), 1, "{\"label\":\"\",\"creatorId\":\""+id+"\"}"));
    }
    @Test void malformedFieldsAndPrimitives() {
        for (String j : List.of("{\"deleted\":null}","{\"deleted\":0}","{\"deleted\":\"false\"}","{\"deleted\":false,\"deleted\":true}")) rejects(SubobjectKind.POT_HEADER,j);
        for (String j : List.of("{\"name\":null,\"userId\":null}","{\"name\":\"A\"}","{\"name\":\"A\",\"userId\":false}","{\"name\":\"A\",\"userId\":\"1-1-1-1-1\"}")) rejects(SubobjectKind.SHAREHOLDER_DETAILS,j);
        for (String date : List.of("2026-02-30","2026-1-02","2026-11-02Z","2026-11-02T12:00:00"))
            rejects(SubobjectKind.EXPENSE_DETAILS,"{\"label\":\"E\",\"date\":\""+date+"\"}");
        for (String uuid : List.of("1-1-1-1-1","FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF","bad"))
            rejects(SubobjectKind.POT_DETAILS,"{\"label\":\"E\",\"creatorId\":\""+uuid+"\"}");
    }
    @Test void fractionsAreNeverRepaired() {
        for (String pair : List.of("\"n\":\"2\",\"d\":\"4\"", "\"n\":\"0\",\"d\":\"2\"", "\"n\":\"1\",\"d\":\"-2\"", "\"n\":\"1\",\"d\":\"0\"",
                "\"n\":\"01\",\"d\":\"1\"","\"n\":\"-0\",\"d\":\"1\"","\"n\":\"+1\",\"d\":\"1\"","\"n\":\"1e3\",\"d\":\"1\"",
                "\"n\":1,\"d\":\"1\"", "\"n\":\"1.0\",\"d\":\"1\"", "\"n\":\"1\",\"n\":\"2\",\"d\":\"1\"", "\"n\":\"1\""))
            rejects(SubobjectKind.SHAREHOLDER_WEIGHT,"{\"defaultWeight\":{"+pair+"}}");
        for (String n : List.of("0","-1")) rejects(SubobjectKind.SHAREHOLDER_WEIGHT,"{\"defaultWeight\":{\"n\":\""+n+"\",\"d\":\"1\"}}");
        rejects(SubobjectKind.EXPENSE_FINANCIALS, fixtures().get(SubobjectKind.EXPENSE_FINANCIALS).replace("\"n\":\"0\"", "\"n\":\"-1\""));
    }
    @Test void financialArrayIsCanonicalAndComplete() {
        String base = fixtures().get(SubobjectKind.EXPENSE_FINANCIALS);
        String entry = "{\"shareholderId\":\""+id+"\",\"specificWeight\":null}";
        rejects(SubobjectKind.EXPENSE_FINANCIALS,base.replace(entry,entry+","+entry));
        rejects(SubobjectKind.EXPENSE_FINANCIALS,base.replace(entry,""));
        rejects(SubobjectKind.EXPENSE_FINANCIALS,base.replace(entry,entry+","+entry.replace(id,"00000000-0000-0000-0000-000000000001")));
        rejects(SubobjectKind.EXPENSE_FINANCIALS,base.replace("\"specificWeight\":null","\"specificWeight\":1"));
        rejects(SubobjectKind.EXPENSE_FINANCIALS,base.replace(",\"specificWeight\":null",""));
        String present = base.replace("\"specificWeight\":null","\"specificWeight\":{\"n\":\"1\",\"d\":\"2\"}");
        var value = SubobjectJson.decode(key(SubobjectKind.EXPENSE_FINANCIALS), 1, present);
        assertNotEquals(value, SubobjectJson.decode(key(SubobjectKind.EXPENSE_FINANCIALS),1,base));
        assertEquals(value,SubobjectJson.decode(value.key(),1,SubobjectJson.encode(value)));
    }
}
