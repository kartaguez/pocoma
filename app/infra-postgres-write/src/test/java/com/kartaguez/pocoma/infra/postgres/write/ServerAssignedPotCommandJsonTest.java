package com.kartaguez.pocoma.infra.postgres.write;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.*;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseDetails;
import com.kartaguez.pocoma.domain.pot.expense.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.revision.InconsistentPotFactsException;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.PotCommandStore;
import com.kartaguez.pocoma.infra.postgres.write.pot.PotCommandJson;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

class ServerAssignedPotCommandJsonTest {
    @Test
    void createPotWithNullPotIdRoundTripsCleanly() {
        var cmd =
                new PotCommand(
                        UUID.randomUUID(),
                        new Eui("issuer", "subject"),
                        "token",
                        null,
                        0,
                        new CreatePot(new Label("Vacances")));
        String encoded = PotCommandJson.encode(cmd);
        assertEquals("{\"label\":\"Vacances\"}", encoded);
        var decoded =
                PotCommandJson.decodeCommand(
                        cmd.requestId(),
                        cmd.requester(),
                        cmd.bindingToken(),
                        null,
                        0,
                        "CreatePot",
                        encoded);
        assertEquals(cmd, decoded);
        assertNull(decoded.potId());
    }

    @Test
    void serverAssignedAddShareholdersAndCreateExpenseUsePayloadV3() {
        var pot = new PotId(UUID.randomUUID());
        var payer = new ShareholderId(UUID.randomUUID());

        var addSh =
                new PotCommand(
                        UUID.randomUUID(),
                        new Eui("issuer", "subject"),
                        "token",
                        pot,
                        1,
                        new AddShareholders(
                                List.of(
                                        ShareholderCreation.of(
                                                new Name("Owner"),
                                                Attachment.SELF,
                                                new Weight(Fraction.ONE)),
                                        ShareholderCreation.of(
                                                new Name("Guest"),
                                                Attachment.FREE,
                                                new Weight(new Fraction(3, 2))))));
        String addJson = PotCommandJson.encode(addSh);
        assertTrue(addJson.contains("\"payloadVersion\":3"));
        assertFalse(addJson.contains("\"id\""));
        assertEquals(
                addSh,
                PotCommandJson.decodeCommand(
                        addSh.requestId(),
                        addSh.requester(),
                        addSh.bindingToken(),
                        pot,
                        1,
                        "AddShareholders",
                        addJson));
        // Old workers without payloadVersion awareness fail closed
        assertThrows(
                InconsistentPotFactsException.class,
                () -> PotCommandJson.decode("AddShareholders", addJson));

        var createExp =
                new PotCommand(
                        UUID.randomUUID(),
                        new Eui("issuer", "subject"),
                        "token",
                        pot,
                        2,
                        new CreateExpense(
                                new Label("Dinner"),
                                LocalDate.of(2026, 11, 14),
                                new Amount(new Fraction(10, 3)),
                                payer,
                                List.of(
                                        new ExpenseFinancials.Participation(
                                                payer, Optional.empty()))));
        String expJson = PotCommandJson.encode(createExp);
        assertTrue(expJson.contains("\"payloadVersion\":3"));
        assertEquals(
                createExp,
                PotCommandJson.decodeCommand(
                        createExp.requestId(),
                        createExp.requester(),
                        createExp.bindingToken(),
                        pot,
                        2,
                        "CreateExpense",
                        expJson));
        assertThrows(
                InconsistentPotFactsException.class,
                () -> PotCommandJson.decode("CreateExpense", expJson));
    }

    @Test
    void historicalCreateExpenseV1RemainsDecodable() {
        var pot = new PotId(UUID.randomUUID());
        var expenseId = new ExpenseId(UUID.randomUUID());
        var payer = new ShareholderId(UUID.randomUUID());
        var legacy =
                new PotCommand(
                        UUID.randomUUID(),
                        new Eui("issuer", "subject"),
                        "token",
                        pot,
                        2,
                        new CreateExpense(
                                new ExpenseDetails(
                                        pot,
                                        expenseId,
                                        new Label("Old"),
                                        LocalDate.of(2026, 11, 1)),
                                ExpenseFinancials.of(
                                        pot,
                                        expenseId,
                                        new Amount(Fraction.ONE),
                                        payer,
                                        List.of(
                                                new ExpenseFinancials.Participation(
                                                        payer, Optional.empty())))));
        String json = PotCommandJson.encode(legacy);
        assertFalse(json.contains("payloadVersion"));
        assertEquals(
                legacy,
                PotCommandJson.decodeCommand(
                        legacy.requestId(),
                        legacy.requester(),
                        legacy.bindingToken(),
                        pot,
                        2,
                        "CreateExpense",
                        json));
    }

    @Test
    void createdResourcesCodecRoundTripsAllCreationFamilies() {
        var potRes = PotCommandStore.CreatedResources.pot(UUID.randomUUID());
        assertEquals(
                potRes,
                PotCommandJson.decodeCreatedResources(PotCommandJson.encodeCreatedResources(potRes)));

        var expRes = PotCommandStore.CreatedResources.expense(UUID.randomUUID());
        assertEquals(
                expRes,
                PotCommandJson.decodeCreatedResources(PotCommandJson.encodeCreatedResources(expRes)));

        var shRes =
                PotCommandStore.CreatedResources.shareholders(
                        List.of(
                                new PotCommandStore.CreatedShareholder(0, UUID.randomUUID()),
                                new PotCommandStore.CreatedShareholder(1, UUID.randomUUID())));
        assertEquals(
                shRes,
                PotCommandJson.decodeCreatedResources(PotCommandJson.encodeCreatedResources(shRes)));

        assertNull(PotCommandJson.encodeCreatedResources(null));
        assertNull(PotCommandJson.decodeCreatedResources(null));
    }
}
