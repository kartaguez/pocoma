package com.kartaguez.pocoma.supra.web.read;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.projection.CommandTerminalResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class CommandResultCreatedResourcesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ExternalIdentity EUI = new ExternalIdentity("issuer", "subject");

    @Test
    void rejectedCreatePotAllowsNullPotIdAndForbidsCreatedResources() {
        var rejected =
                new CommandTerminalResult(
                        UUID.randomUUID(), "CreatePot", EUI, null, "REJECTED", null, "INVALID_BINDING", null);
        assertNull(rejected.potId());
        assertEquals("REJECTED", rejected.outcome());

        assertThrows(
                NullPointerException.class,
                () ->
                        new CommandTerminalResult(
                                UUID.randomUUID(),
                                "CreatePot",
                                EUI,
                                null,
                                "CHANGED",
                                1L,
                                null,
                                null));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CommandTerminalResult(
                                UUID.randomUUID(),
                                "CreatePot",
                                EUI,
                                null,
                                "REJECTED",
                                null,
                                "INVALID_BINDING",
                                CommandTerminalResult.CreatedResources.pot(UUID.randomUUID())));
    }

    @Test
    void readDtoSerializesCreatedResourcesCleanlyWithoutPrivateIds() throws Exception {
        UUID reqId = UUID.randomUUID();
        UUID potId = UUID.randomUUID();
        UUID sh0 = UUID.randomUUID();
        UUID sh1 = UUID.randomUUID();

        var dto =
                new ReadDtos.Command(
                        reqId,
                        "AddShareholders",
                        potId,
                        "COMPLETED",
                        2L,
                        null,
                        new ReadDtos.CreatedResources(
                                null,
                                null,
                                List.of(
                                        new ReadDtos.CreatedShareholder(0, sh0),
                                        new ReadDtos.CreatedShareholder(1, sh1))));
        var tree = JSON.readTree(JSON.writeValueAsString(dto));
        assertEquals(reqId.toString(), tree.get("requestId").textValue());
        assertEquals(potId.toString(), tree.get("potId").textValue());
        var created = tree.get("createdResources");
        assertFalse(created.has("potId"));
        assertFalse(created.has("expenseId"));
        assertEquals(2, created.get("shareholders").size());
        assertEquals(0, created.get("shareholders").get(0).get("inputIndex").intValue());
        assertEquals(sh0.toString(), created.get("shareholders").get(0).get("shareholderId").textValue());
        assertEquals(1, created.get("shareholders").get(1).get("inputIndex").intValue());
        assertEquals(sh1.toString(), created.get("shareholders").get(1).get("shareholderId").textValue());
        assertFalse(tree.toString().contains("userId"));
    }
}
