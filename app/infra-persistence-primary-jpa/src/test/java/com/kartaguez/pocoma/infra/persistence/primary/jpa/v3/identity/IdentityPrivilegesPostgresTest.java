package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;
import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.identity.v3.*;
import com.kartaguez.pocoma.engine.write.identity.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;

import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;

@Testcontainers
class IdentityPrivilegesPostgresTest extends IdentityEngineFixture {
    @Test
    void l3_internal_admission_idempotence() throws Exception {
        var e = key("idempotence");
        var req = request(new IdentityRequest.Registration(), e, null, e);
        admitRequest(req);
        var original = tx.read(c -> store.load(req.requestId()).orElseThrow());
        assertEquals(original, tx.write(() -> store.insertPending(req)));
        assertEquals(1, count("v3_identity_request"));
        assertEquals(1, count("v3_request_position"));
        assertEquals(
                1L,
                admin.queryForObject(
                        "select position from v3_request_counter where family='IDENTITY_REQUEST'",
                        Long.class));
        assertEquals(0, count("consumption_slot"));
        var other =
                request(
                        new IdentityRequest.Registration(),
                        key("substitution"),
                        null,
                        key("substitution"));
        var substituted =
                new IdentityRequest(
                        req.requestId(),
                        other.requesterEui(),
                        other.operation(),
                        null,
                        other.createdAt(),
                        other.admission());
        assertThrows(RuntimeException.class, () -> admitRequest(substituted));
        assertEquals(original, tx.read(c -> store.load(req.requestId()).orElseThrow()));
        execute(req);
        assertEquals(
                IdentityRequestStore.Status.TERMINAL,
                tx.write(() -> store.insertPending(req)).status());
        assertCounts(1, 1, 1, 2, 1);
        assertThrows(
                RuntimeException.class,
                () ->
                        tx.write(
                                () -> {
                                    jdbc.update(
                                            "update v3_identity_request set"
                                                + " requester_subject='changed' where request_id=?",
                                            req.requestId());
                                    return null;
                                }));
    }

    @Test
    void l3_effective_privileges() {
        String role = schema + "_runtime";
        for (String t :
                List.of(
                        "v3_identity_request",
                        "v3_eui_authority",
                        "v3_pocoma_user",
                        "v3_eui_version",
                        "v3_identity_request_result",
                        "v3_identity_event",
                        "v3_identity_completion_event",
                         "v3_event_counter",
                        "v3_event_position",
                        "v3_request_counter",
                        "v3_request_position",
                        "consumption_slot")) admin.execute("revoke all on " + t + " from " + role);
        admin.execute(
                "grant select on"
                    + " v3_identity_request,v3_eui_authority,v3_pocoma_user,v3_eui_version,v3_identity_request_result,v3_identity_event,v3_identity_completion_event,v3_event_counter,v3_event_position,v3_request_counter,v3_request_position,consumption_slot"
                    + " to "
                        + role);
        admin.execute(
                "grant insert on"
                    + " v3_eui_authority,v3_pocoma_user,v3_eui_version,v3_identity_request_result,v3_identity_event,v3_identity_completion_event,v3_event_position"
                    + " to "
                        + role);
        admin.execute("grant update(current_version) on v3_eui_authority to " + role);
        admin.execute("grant update(status) on v3_identity_request to " + role);
        admin.execute("grant update(position) on v3_event_counter to " + role);
        admin.execute(
                "grant"
                    + " insert(consumption_id,consumable_id,partition_hash,partition_id),update(claim_token,claimed_until,retry_after,attempt_count,state),delete"
                    + " on consumption_slot to "
                        + role);
        var e = key("permissions");
        var req = request(new IdentityRequest.Registration(), e, null, e);
        assertThrows(RuntimeException.class, () -> admitRequest(req));
        assertEquals(0, count("v3_identity_request"));
        // The separate controlled injector admits; the least-privilege runtime executes all ops.
        var admissionTx =
                new com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions(
                        adminSource, manager(adminSource), 5);
        var admission = new JdbcIdentityRequestStore(adminSource, admissionTx);
        admissionTx.write(() -> admission.insertPending(req));
        execute(req);
        var a = current(e);
        var target = key("target");
        var link = request(new IdentityRequest.Link(target), e, a.bindingToken(), target);
        admissionTx.write(() -> admission.insertPending(link));
        execute(link);
        var unlink = request(new IdentityRequest.Unlink(target), e, a.bindingToken(), target);
        admissionTx.write(() -> admission.insertPending(unlink));
        execute(unlink);
        assertCounts(1, 3, 3, 4, 3);
        assertEquals(
                List.of(req.requestId(), link.requestId(), unlink.requestId()),
                admin.queryForList(
                        "select request_id from v3_request_position where family='IDENTITY_REQUEST'"
                                + " order by position",
                        UUID.class));
        assertThrows(
                RuntimeException.class,
                () ->
                        tx.write(
                                () -> {
                                    jdbc.update("delete from v3_eui_authority");
                                    return null;
                                }));
        assertThrows(
                RuntimeException.class,
                () ->
                        tx.write(
                                () -> {
                                    jdbc.update("update v3_eui_version set binding_token='forged'");
                                    return null;
                                }));
    }

    @Test
    void l3_wrong_authority() {
        var other =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        assertThrows(IllegalArgumentException.class, () -> new JdbcIdentityRequestStore(other, tx));
        assertThrows(IllegalArgumentException.class, () -> new JdbcIdentityAuthority(other, tx));
        assertThrows(RuntimeException.class, () -> store.load(UUID.randomUUID()));
    }
}
