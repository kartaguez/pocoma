package com.kartaguez.pocoma.engine.admit.command.v3;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.HmacAdmissionProtection;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionAssociation;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionFailure;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionFields;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionProtection;
import com.kartaguez.pocoma.contract.writeadmission.CommandAdmissionInput;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.contract.writeadmission.*;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;
import java.util.function.*;

class V3AdmissionCanonicalizationTest {
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
    void equivalentObjectsAndExactRationals() {
        var a =
                decode(
                        input(
                                "UpdateShareholderWeights",
                                Map.of(
                                        "targets",
                                        List.of(Map.of("id", SID, "weight", fraction("2", "4"))))));
        var b =
                decode(
                        input(
                                "UpdateShareholderWeights",
                                Map.of(
                                        "targets",
                                        List.of(
                                                Map.of(
                                                        "weight",
                                                        fraction("-1", "-2"),
                                                        "id",
                                                        SID)))));
        assertArrayEquals(a.canonical(1), b.canonical(1));
    }

    @Test
    void orderedAndUnorderedCollections() {
        String other = "00000000-0000-0000-0000-000000000004";
        var p = new LinkedHashMap<>(payload("UpdateExpenseFinancials"));
        p.put("participants", List.of(participant(SID), participant(other)));
        var q = new LinkedHashMap<>(p);
        q.put("participants", List.of(participant(other), participant(SID)));
        assertArrayEquals(
                decode(input("UpdateExpenseFinancials", p)).canonical(1),
                decode(input("UpdateExpenseFinancials", q)).canonical(1));
        var x = Map.of("id", SID, "name", "A", "attachment", "KEEP");
        var y = Map.of("id", other, "name", "B", "attachment", "KEEP");
        assertFalse(
                Arrays.equals(
                        decode(input("UpdateShareholderDetails", Map.of("targets", List.of(x, y))))
                                .canonical(1),
                        decode(input("UpdateShareholderDetails", Map.of("targets", List.of(y, x))))
                                .canonical(1)));
    }

    @Test
    void canonicalFramingCannotAlias() {
        assertFalse(
                Arrays.equals(
                        AdmissionProtection.frame("ab", "c"),
                        AdmissionProtection.frame("a", "bc")));
        assertFalse(
                Arrays.equals(
                        AdmissionFields.canonical(List.of("a", "b")),
                        AdmissionFields.canonical("ab")));
        String high = Character.toString((char) 0xD800);
        String low = Character.toString((char) 0xDC00);
        for (String invalid : List.of(high, low, high + "a", "a" + low, high + high)) {
            assertEquals(
                    400,
                    assertThrows(
                                    AdmissionFailure.class,
                                    () -> AdmissionFields.canonical(Map.of("label", invalid)))
                            .status());
            assertThrows(AdmissionFailure.class, () -> AdmissionProtection.frame(invalid));
            assertThrows(
                    AdmissionFailure.class,
                    () -> decode(input("CreatePot", Map.of("label", invalid))));
            assertThrows(
                    AdmissionFailure.class,
                    () -> protection().scope("issuer", invalid, Optional.empty(), "COMMAND", "CreatePot", "k"));
        }
        String scalar = new String(Character.toChars(0x1F600));
        assertArrayEquals(scalar.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.util.Arrays.copyOfRange(AdmissionProtection.frame(scalar), 4, 8));
        assertFalse(Arrays.equals(AdmissionFields.canonical(scalar), AdmissionFields.canonical("?")));
    }

    @Test
    void scopeSeparatesIdentityOperationAndClient() {
        var p = protection();
        var base = p.scope("i", "s", Optional.empty(), "COMMAND", "CreatePot", "key");
        for (var changed :
                List.of(
                        p.scope("i2", "s", Optional.empty(), "COMMAND", "CreatePot", "key"),
                        p.scope("i", "s2", Optional.empty(), "COMMAND", "CreatePot", "key"),
                        p.scope("i", "s", Optional.of(""), "COMMAND", "CreatePot", "key"),
                        p.scope("i", "s", Optional.empty(), "IDENTITY", "CreatePot", "key"),
                        p.scope("i", "s", Optional.empty(), "COMMAND", "DeletePot", "key")))
            assertFalse(Arrays.equals(base, changed));
    }

    @Test
    void bindingAndAttachmentAreIntent() {
        var a = input("CreatePot", payload("CreatePot"));
        var b = new LinkedHashMap<>(a);
        b.put("bindingToken", "different");
        assertFalse(Arrays.equals(decode(a).canonical(1), decode(b).canonical(1)));
        var x = Map.of("targets", List.of(Map.of("id", SID, "name", "A", "attachment", "SELF")));
        var y = Map.of("targets", List.of(Map.of("id", SID, "name", "A", "attachment", "KEEP")));
        assertFalse(
                Arrays.equals(
                        decode(input("UpdateShareholderDetails", x)).canonical(1),
                        decode(input("UpdateShareholderDetails", y)).canonical(1)));
    }

    @Test
    void oldIntentAfterRuleChange() {
        var m = new Memory();
        var input = decode(input("CreatePot", Map.of("label", "long label")));
        var id = m.service(4096).admit(principal(), "key", input);
        assertEquals(id, m.service(1).admit(principal(), "key", input));
        assertEquals(1, m.inserts);
        assertEquals(1, m.reservations);
    }

    @Test
    void currentAndHistoricalValidationStaySeparate() {
        var m = new Memory();
        var i = decode(input("CreatePot", Map.of("label", "long label")));
        m.service(4096).admit(principal(), "key", i);
        assertEquals(
                400,
                assertThrows(
                                AdmissionFailure.class,
                                () -> m.service(1).admit(principal(), "new", i))
                        .status());
        assertEquals(1, m.inserts);
    }

    @Test
    void keyRotationAndMissingKeyFailClosed() {
        var input = decode(input("CreatePot", payload("CreatePot")));
        byte[] old = new byte[32], next = new byte[32];
        next[0] = 1;
        var a =
                new AdmissionAssociation(
                        UUID.randomUUID(),
                        "COMMAND",
                        "CreatePot",
                        1,
                        "stable",
                        "old",
                        new byte[32],
                        protection("old", Map.of("old", old)).intent("old", 1, input.canonical(1)),
                        Instant.now());
        assertTrue(
                protection("new", Map.of("old", old, "new", next)).matches(a, input.canonical(1)));
        assertEquals(
                503,
                assertThrows(
                                AdmissionFailure.class,
                                () ->
                                        protection("new", Map.of("new", next))
                                                .matches(a, input.canonical(1)))
                        .status());
        assertThrows(
                AdmissionFailure.class,
                () -> new HmacAdmissionProtection("stable", null, "k", Map.of("k", old)));
        assertEquals(503, assertThrows(AdmissionFailure.class, () -> input.canonical(99)).status());
    }
}
