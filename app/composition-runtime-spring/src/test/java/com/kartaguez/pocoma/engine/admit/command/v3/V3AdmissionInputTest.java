package com.kartaguez.pocoma.engine.admit.command.v3;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.HmacAdmissionProtection;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionAssociation;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionFailure;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionProtection;
import com.kartaguez.pocoma.contract.writeadmission.CommandAdmissionInput;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.contract.writeadmission.*;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.function.*;

class V3AdmissionInputTest {
    static final String POT = "00000000-0000-0000-0000-000000000001",
            SID = "00000000-0000-0000-0000-000000000002",
            EID = "00000000-0000-0000-0000-000000000003";
    static final PotCommand.Eui EUI = new PotCommand.Eui("issuer", "subject");

    static Map<String, Object> fraction(String a, String b) {
        return Map.of("numerator", a, "denominator", b);
    }

    static Map<String, Object> input(String op, Object payload) {
        return new LinkedHashMap<>(
                Map.of(
                        "operation",
                        op,
                        "potId",
                        POT,
                        "observedVersion",
                        op.equals("CreatePot") ? 0 : 1,
                        "bindingToken",
                        "binding-token",
                        "payload",
                        payload));
    }

    static Map<String, Object> payload(String op) {
        return switch (op) {
            case "CreatePot", "UpdatePotDetails" -> Map.of("label", "Café");
            case "DeletePot" -> Map.of();
            case "AddShareholders" ->
                    Map.of(
                            "targets",
                            List.of(
                                    Map.of(
                                            "id",
                                            SID,
                                            "name",
                                            "A",
                                            "attachment",
                                            "SELF",
                                            "weight",
                                            fraction("2", "4"))));
            case "UpdateShareholderDetails" ->
                    Map.of(
                            "targets",
                            List.of(Map.of("id", SID, "name", "A", "attachment", "KEEP")));
            case "UpdateShareholderWeights" ->
                    Map.of("targets", List.of(Map.of("id", SID, "weight", fraction("1", "2"))));
            case "DeleteShareholder" -> Map.of("id", SID);
            case "CreateExpense" ->
                    Map.of(
                            "id",
                            EID,
                            "label",
                            "E",
                            "date",
                            "2026-11-04",
                            "amount",
                            fraction("10", "3"),
                            "payerId",
                            SID,
                            "participants",
                            List.of(participant(SID)));
            case "UpdateExpenseDetails" -> Map.of("id", EID, "label", "E", "date", "2026-11-04");
            case "UpdateExpenseFinancials" ->
                    Map.of(
                            "id",
                            EID,
                            "amount",
                            fraction("10", "3"),
                            "payerId",
                            SID,
                            "participants",
                            List.of(participant(SID)));
            case "DeleteExpense" -> Map.of("id", EID);
            default -> throw new AssertionError();
        };
    }

    static Map<String, Object> participant(String id) {
        var p = new LinkedHashMap<String, Object>();
        p.put("id", id);
        p.put("weight", null);
        return p;
    }

    static CommandAdmissionInput decode(Object input) {
        return CommandAdmissionInput.decode(input, EUI, 1024);
    }

    static AdmissionProtection protection(String current, Map<String, byte[]> keys) {
        return new HmacAdmissionProtection("stable", new byte[32], current, keys);
    }

    static AdmissionProtection protection() {
        return protection("k1", Map.of("k1", new byte[32]));
    }

    static AuthenticatedExternalPrincipal principal() {
        var now = Instant.now();
        return new AuthenticatedExternalPrincipal(
                "issuer",
                "subject",
                now.minusSeconds(1),
                now.minusSeconds(1),
                now.plusSeconds(60),
                Set.of());
    }

    static class Memory implements AdmitCommandV3.Store, AttemptTransactions {
        AdmissionAssociation association;
        int inserts, reads, reservations;

        public Optional<AdmissionAssociation> find(byte[] scope) {
            reads++;
            return association != null && Arrays.equals(scope, association.scopeFingerprint())
                    ? Optional.of(association)
                    : Optional.empty();
        }

        public boolean reserve(AdmissionAssociation a) {
            reservations++;
            association = a;
            return true;
        }

        public void insert(PotCommand command) {
            inserts++;
        }

        public <T> T read(Function<RecoveryReadContext, T> action, Guard guard) {
            return action.apply(() -> {});
        }

        public <T> T write(Supplier<T> action, Guard guard) {
            return action.get();
        }

        public void poison(Throwable t) {}

        public void requirePublicationClosed() {}

        AdmitCommandV3 service(int text) {
            return new AdmitCommandV3(this, this, protection(), Clock.systemUTC(), text);
        }
    }

    @Test
    void allElevenOperationsMapExactly() {
        for (var op :
                List.of(
                        "CreatePot",
                        "UpdatePotDetails",
                        "DeletePot",
                        "AddShareholders",
                        "UpdateShareholderDetails",
                        "UpdateShareholderWeights",
                        "DeleteShareholder",
                        "CreateExpense",
                        "UpdateExpenseDetails",
                        "UpdateExpenseFinancials",
                        "DeleteExpense")) {
            var c = decode(input(op, payload(op))).command(UUID.randomUUID());
            assertEquals(op, c.intent().getClass().getSimpleName());
            assertEquals(EUI, c.requester());
            System.out.println("I2_MAPPING " + op);
        }
    }

    @Test
    void malformedTargetsRejectedBeforeStore() {
        for (var targets :
                List.of(
                        List.of(),
                        List.of(
                                Map.of(
                                        "id",
                                        SID,
                                        "name",
                                        "A",
                                        "attachment",
                                        "SELF",
                                        "weight",
                                        fraction("1", "1")),
                                Map.of(
                                        "id",
                                        SID,
                                        "name",
                                        "B",
                                        "attachment",
                                        "FREE",
                                        "weight",
                                        fraction("1", "1")))))
            assertThrows(
                    AdmissionFailure.class,
                    () -> decode(input("AddShareholders", Map.of("targets", targets))));
    }

    @Test
    void exactFractionsAndPublicIds() {
        var canonicalDate = new LinkedHashMap<>(payload("UpdateExpenseDetails"));
        var equivalentDate = new LinkedHashMap<>(canonicalDate);
        equivalentDate.put("date", "+002026-11-04");
        assertArrayEquals(
                decode(input("UpdateExpenseDetails", canonicalDate)).canonical(1),
                decode(input("UpdateExpenseDetails", equivalentDate)).canonical(1));
        var n = input("CreatePot", payload("CreatePot"));
        n.put("potId", "1-1-1-1-1");
        assertThrows(AdmissionFailure.class, () -> decode(n));
        assertThrows(
                AdmissionFailure.class,
                () ->
                        decode(
                                input(
                                        "UpdateShareholderWeights",
                                        Map.of(
                                                "targets",
                                                List.of(
                                                        Map.of(
                                                                "id",
                                                                SID,
                                                                "weight",
                                                                fraction("1", "0")))))));
        assertThrows(
                AdmissionFailure.class,
                () ->
                        decode(
                                input(
                                        "UpdateShareholderWeights",
                                        Map.of(
                                                "targets",
                                                List.of(
                                                        Map.of(
                                                                "id",
                                                                SID,
                                                                "weight",
                                                                fraction(
                                                                        "1".repeat(1025),
                                                                        "1")))))));
    }

    @Test
    void clientCannotChooseRequesterOrUser() {
        var n = input("CreatePot", payload("CreatePot"));
        n.put("requesterEui", Map.of("issuer", "x", "subject", "y"));
        assertThrows(AdmissionFailure.class, () -> decode(n));
        assertThrows(
                AdmissionFailure.class,
                () ->
                        decode(
                                input(
                                        "AddShareholders",
                                        Map.of(
                                                "targets",
                                                List.of(
                                                        Map.of(
                                                                "id",
                                                                SID,
                                                                "name",
                                                                "A",
                                                                "attachment",
                                                                "SELF",
                                                                "userId",
                                                                SID,
                                                                "weight",
                                                                fraction("1", "1")))))));
    }

    @Test
    void validationFailureDoesNotCallStore() {
        var m = new Memory();
        assertThrows(
                AdmissionFailure.class,
                () ->
                        m.service(1)
                                .admit(
                                        principal(),
                                        "key",
                                        decode(input("CreatePot", Map.of("label", "too long")))));
        assertEquals(0, m.inserts);
        assertEquals(0, m.reservations);
        assertThrows(
                AdmissionFailure.class,
                () ->
                        m.service(4096)
                                .admit(
                                        principal(),
                                        " ",
                                        decode(input("CreatePot", payload("CreatePot")))));
    }
}
