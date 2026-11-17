package com.kartaguez.pocoma.infra.postgres.write.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

class L73TerminationIdentityPostgresTest extends IdentityEngineFixture {
    private static final List<String> FAMILIES =
            List.of("POT", "COMMAND_COMPLETION", "INVITATION", "IDENTITY", "IDENTITY_COMPLETION");

    /** L2M-E01-E (Identity REJECTED): only IDENTITY_COMPLETION advances; IDENTITY unchanged. */
    @Test
    void l2m_e01_e_rejectedIdentityRequestOnlyAdvancesIdentityCompletion() {
        var eui = key("dup-eui");
        executeAdmitted(request(new IdentityRequest.Registration(), eui, null, eui));
        var beforeCounters = counters();
        var beforePositions = positionCounts();
        assertEquals(2L, beforeCounters.get("IDENTITY"));
        assertEquals(1L, beforeCounters.get("IDENTITY_COMPLETION"));

        // Duplicate Registration -> REJECTED
        var rejectedReq = request(new IdentityRequest.Registration(), eui, null, eui);
        executeAdmitted(rejectedReq);

        var afterCounters = counters();
        var afterPositions = positionCounts();
        assertEquals(
                beforeCounters.get("IDENTITY_COMPLETION") + 1,
                afterCounters.get("IDENTITY_COMPLETION"));
        assertEquals(
                beforePositions.get("IDENTITY_COMPLETION") + 1,
                afterPositions.get("IDENTITY_COMPLETION"));
        for (String family : List.of("POT", "COMMAND_COMPLETION", "INVITATION", "IDENTITY")) {
            assertEquals(beforeCounters.get(family), afterCounters.get(family), family);
            assertEquals(beforePositions.get(family), afterPositions.get(family), family);
        }
        assertEquals(
                "REJECTED",
                admin.queryForObject(
                        "select outcome from v3_identity_request_result where request_id=?",
                        String.class,
                        rejectedReq.requestId()));
    }

    private Map<String, Long> counters() {
        var map = new TreeMap<String, Long>();
        for (String f : FAMILIES) {
            map.put(
                    f,
                    admin.queryForObject(
                            "select position from v3_event_counter where family=?",
                            Long.class,
                            f));
        }
        return map;
    }

    private Map<String, Long> positionCounts() {
        var map = new TreeMap<String, Long>();
        for (String f : FAMILIES) {
            map.put(
                    f,
                    admin.queryForObject(
                            "select count(*) from v3_event_position where family=?",
                            Long.class,
                            f));
        }
        return map;
    }
}
