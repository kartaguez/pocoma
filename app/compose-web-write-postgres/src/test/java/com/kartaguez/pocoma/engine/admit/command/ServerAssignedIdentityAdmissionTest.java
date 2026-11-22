package com.kartaguez.pocoma.engine.admit.command;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.writeadmission.AdmissionFailure;
import com.kartaguez.pocoma.contract.writeadmission.CommandAdmissionInput;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;

import org.junit.jupiter.api.Test;

import java.util.*;

class ServerAssignedIdentityAdmissionTest {
    private static final String POT = "00000000-0000-0000-0000-000000000001";
    private static final String SID = "00000000-0000-0000-0000-000000000002";
    private static final String EID = "00000000-0000-0000-0000-000000000003";
    private static final PotCommand.Eui EUI = new PotCommand.Eui("issuer", "subject");

    private static Map<String, Object> fraction(String n, String d) {
        return Map.of("numerator", n, "denominator", d);
    }

    @Test
    void createPotRejectsClientPotIdAndAdmitsWithNullPotId() {
        var withPotId = new LinkedHashMap<String, Object>();
        withPotId.put("operation", "CreatePot");
        withPotId.put("potId", POT);
        withPotId.put("observedVersion", 0);
        withPotId.put("bindingToken", "binding-token");
        withPotId.put("payload", Map.of("label", "Trip"));

        var failure =
                assertThrows(
                        AdmissionFailure.class,
                        () -> CommandAdmissionInput.decode(withPotId, EUI, 1024));
        assertEquals(400, failure.status());

        withPotId.remove("potId");
        var decoded = CommandAdmissionInput.decode(withPotId, EUI, 1024);
        var cmd = decoded.command(UUID.randomUUID());
        assertNull(cmd.potId());
        assertInstanceOf(PotCommand.CreatePot.class, cmd.intent());
    }

    @Test
    void createPotIdempotencyReplayAndConflictWithoutClientPotId() {
        var memory = new AdmissionInputTest.Memory();
        var service = memory.service(1024);
        var principal = AdmissionInputTest.principal();

        var firstInput =
                CommandAdmissionInput.decode(
                        Map.of(
                                "operation",
                                "CreatePot",
                                "observedVersion",
                                0,
                                "bindingToken",
                                "binding-token",
                                "payload",
                                Map.of("label", "Trip A")),
                        EUI,
                        1024);
        var firstRequestId = service.admit(principal, "idem-create-pot", firstInput);
        var replayRequestId = service.admit(principal, "idem-create-pot", firstInput);
        assertEquals(firstRequestId, replayRequestId);
        assertEquals(1, memory.inserts);

        var conflictingInput =
                CommandAdmissionInput.decode(
                        Map.of(
                                "operation",
                                "CreatePot",
                                "observedVersion",
                                0,
                                "bindingToken",
                                "binding-token",
                                "payload",
                                Map.of("label", "Trip B")),
                        EUI,
                        1024);
        var conflict =
                assertThrows(
                        AdmissionFailure.class,
                        () -> service.admit(principal, "idem-create-pot", conflictingInput));
        assertEquals(409, conflict.status());
    }

    @Test
    void addShareholdersRejectsClientTargetIdAndPreservesInputOrder() {
        var withId =
                Map.of(
                        "operation",
                        "AddShareholders",
                        "potId",
                        POT,
                        "observedVersion",
                        1,
                        "bindingToken",
                        "binding-token",
                        "payload",
                        Map.of(
                                "targets",
                                List.of(
                                        Map.of(
                                                "id",
                                                SID,
                                                "name",
                                                "Alice",
                                                "attachment",
                                                "SELF",
                                                "weight",
                                                fraction("1", "1")))));
        assertEquals(
                400,
                assertThrows(
                                AdmissionFailure.class,
                                () -> CommandAdmissionInput.decode(withId, EUI, 1024))
                        .status());

        var valid =
                Map.of(
                        "operation",
                        "AddShareholders",
                        "potId",
                        POT,
                        "observedVersion",
                        1,
                        "bindingToken",
                        "binding-token",
                        "payload",
                        Map.of(
                                "targets",
                                List.of(
                                        Map.of(
                                                "name",
                                                "Alice",
                                                "attachment",
                                                "SELF",
                                                "weight",
                                                fraction("1", "1")),
                                        Map.of(
                                                "name",
                                                "Alice",
                                                "attachment",
                                                "FREE",
                                                "weight",
                                                fraction("2", "1")))));
        var cmd = CommandAdmissionInput.decode(valid, EUI, 1024).command(UUID.randomUUID());
        assertTrue(cmd.attachments().isEmpty());
        var intent = assertInstanceOf(PotCommand.AddShareholders.class, cmd.intent());
        assertTrue(intent.serverAssigned());
        assertEquals(2, intent.creations().size());
        assertEquals(PotCommand.Attachment.SELF, intent.creations().get(0).attachment());
        assertEquals(PotCommand.Attachment.FREE, intent.creations().get(1).attachment());
    }

    @Test
    void createExpenseRejectsClientExpenseIdAndKeepsShareholderReferences() {
        var participant = new LinkedHashMap<String, Object>();
        participant.put("id", SID);
        participant.put("weight", null);

        var withId = new LinkedHashMap<String, Object>();
        withId.put("id", EID);
        withId.put("label", "Dinner");
        withId.put("date", "2026-11-14");
        withId.put("amount", fraction("10", "3"));
        withId.put("payerId", SID);
        withId.put("participants", List.of(participant));

        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("operation", "CreateExpense");
        envelope.put("potId", POT);
        envelope.put("observedVersion", 2);
        envelope.put("bindingToken", "binding-token");
        envelope.put("payload", withId);

        assertEquals(
                400,
                assertThrows(
                                AdmissionFailure.class,
                                () -> CommandAdmissionInput.decode(envelope, EUI, 1024))
                        .status());

        withId.remove("id");
        var cmd = CommandAdmissionInput.decode(envelope, EUI, 1024).command(UUID.randomUUID());
        var intent = assertInstanceOf(PotCommand.CreateExpense.class, cmd.intent());
        assertTrue(intent.serverAssigned());
        assertNull(intent.legacyId());
        assertEquals(UUID.fromString(SID), intent.payerId().value());
    }
}
