package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class V3InvitationHttpTest extends V3InvitationFixture {
    @Test
    void concurrentUnlinkAndAttachmentSerializeAtIdentity() throws Exception {
        startPot();
        String guest = binding("guest");
        String secret = invitationToken(create(Instant.parse("2030-01-01T00:00:00Z")));
        UUID unlink, attachment;
        try (var gate = f.worker.write.getDataSource().getConnection()) {
            gate.setAutoCommit(false);
            try (var statement =
                    gate.prepareStatement(
                            "select current_version from v3_eui_authority where issuer=? and"
                                + " subject=? for update")) {
                statement.setString(1, f.http.issuer);
                statement.setString(2, "guest");
                statement.execute();
            }
            unlink =
                    f.accepted(
                            f.post(
                                    "/identity/requests",
                                    "guest",
                                    "concurrent-unlink",
                                    Map.of(
                                            "operation",
                                            "Unlink",
                                            "bindingToken",
                                            guest,
                                            "target",
                                            Map.of("issuer", f.http.issuer, "subject", "guest"))));
            attachment =
                    f.accepted(
                            f.post(
                                    "/commands",
                                    "guest",
                                    "concurrent-attach",
                                    attachBody(guest, secret, f.share)));
            awaitTwoFinalWriteWaiters();
            gate.commit();
        }
        assertEquals(
                "COMPLETED",
                f.poll("/identity/requests/" + unlink + "/result", "guest", null)
                        .get("outcome")
                        .asText());
        var result = f.poll("/commands/" + attachment + "/result", "guest", null);
        boolean attached = result.get("outcome").asText().equals("COMPLETED");
        if (!attached) assertEquals("INVALID_BINDING", result.get("rejection").asText());
        assertEquals(
                attached ? 1 : 0,
                f.worker.write.queryForObject(
                        "select count(*) from v3_shareholder_details where object_id=? and"
                            + " active_to is null and user_id is not null",
                        Integer.class,
                        f.share));
        assertEquals(
                attached ? 1 : 0,
                f.worker.write.queryForObject(
                        "select count(*) from v3_pot_event where request_id=?",
                        Integer.class,
                        attachment));
        assertNull(
                f.worker.write.queryForObject(
                        "select v.user_id from v3_eui_authority a join v3_eui_version v on"
                            + " a.eui_id=v.eui_id and a.current_version=v.version where a.issuer=?"
                            + " and a.subject=?",
                        UUID.class,
                        f.http.issuer,
                        "guest"));
        assertEquals(
                "INVALID_BINDING",
                attach("guest", guest, secret, new UUID(4, 2), "after-concurrent-unlink")
                        .get("rejection")
                        .asText());
    }

    @Test
    void unlinkCannotPreserveStaleInvitationAuthority() throws Exception {
        startPot();
        String guest = binding("guest");
        String secret = invitationToken(create(Instant.parse("2030-01-01T00:00:00Z")));
        try (var gate = f.worker.read.getDataSource().getConnection()) {
            gate.setAutoCommit(false);
            try (var statement =
                    gate.prepareStatement(
                            "select eui_version from read_current_binding where issuer=? and"
                                    + " subject=? for update")) {
                statement.setString(1, f.http.issuer);
                statement.setString(2, "guest");
                statement.execute();
            }
            UUID unlink =
                    f.accepted(
                            f.post(
                                    "/identity/requests",
                                    "guest",
                                    "unlink-guest",
                                    Map.of(
                                            "operation",
                                            "Unlink",
                                            "bindingToken",
                                            guest,
                                            "target",
                                            Map.of("issuer", f.http.issuer, "subject", "guest"))));
            assertEquals(
                    "COMPLETED",
                    f.poll("/identity/requests/" + unlink + "/result", "guest", null)
                            .get("outcome")
                            .asText());
            assertEquals(
                    200, places("guest", guest, secret).statusCode(), "READ lag remains advisory");
            var rejected = attach("guest", guest, secret, f.share, "stale-after-unlink");
            assertEquals("INVALID_BINDING", rejected.get("rejection").asText());
            assertNull(
                    f.worker.write.queryForObject(
                            "select user_id from v3_shareholder_details where object_id=? and"
                                    + " active_to is null",
                            UUID.class,
                            f.share));
            gate.commit();
        }
    }

    @Test
    void confidentialLinkAndRestrictedPlaces() throws Exception {
        startPot();
        String guest = binding("guest");
        UUID id = create(Instant.parse("2030-01-01T00:00:00Z"));
        String secret = invitationToken(id);
        var foreign =
                f.http.request(
                        "GET",
                        "/invitations/by-request/" + id,
                        f.http.jwt("guest"),
                        null,
                        null,
                        Map.of());
        var absent =
                f.http.request(
                        "GET",
                        "/invitations/by-request/" + UUID.randomUUID(),
                        f.http.jwt("guest"),
                        null,
                        null,
                        Map.of());
        assertEquals(404, foreign.statusCode());
        assertEquals(404, absent.statusCode());
        assertEquals(f.http.body(absent).get("code"), f.http.body(foreign).get("code"));
        assertEquals("no-store", foreign.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(
                new String(foreign.body(), java.nio.charset.StandardCharsets.UTF_8)
                        .contains(secret));
        var available = places("guest", guest, secret);
        assertEquals(200, available.statusCode());
        var body = f.http.body(available);
        assertEquals(Set.of("shareholders"), fieldNames(body));
        assertEquals(2, body.get("shareholders").size());
        for (var s : body.get("shareholders"))
            assertEquals(Set.of("shareholderId", "name"), fieldNames(s));
        assertEquals(404, places("guest", guest, secret + "x").statusCode());
        assertEquals(404, places("guest", "foreign-binding", secret).statusCode());
        assertEquals(
                404,
                f.http.request("GET", "/pots/" + f.pot, f.http.jwt("guest"), guest, null, Map.of())
                        .statusCode());
        assertEquals(
                404,
                f.http.request(
                                "GET",
                                "/pots/" + f.pot + "/versions/2/balance",
                                f.http.jwt("guest"),
                                guest,
                                null,
                                Map.of())
                        .statusCode());
        var attached = attach("guest", guest, secret, f.share, "attachment");
        assertEquals("COMPLETED", attached.get("outcome").asText());
        long version = attached.get("resultingPotVersion").asLong();
        ReadMonolithI8Fixture.await(
                () ->
                        f.worker.read.queryForObject(
                                "select coalesce(max(pot_version),0)>=? from read_current_pot where"
                                        + " pot_id=?",
                                Boolean.class,
                                version,
                                f.pot));
        assertEquals(404, places("guest", guest, secret).statusCode());
        assertEquals(
                200,
                f.http.request("GET", "/pots/" + f.pot, f.http.jwt("guest"), guest, null, Map.of())
                        .statusCode());
        // Creator ownership is EUI-based and survives a public Unlink and new Binding occurrence.
        UUID unlink =
                f.accepted(
                        f.post(
                                "/identity/requests",
                                "owner",
                                "unlink-creator",
                                Map.of(
                                        "operation",
                                        "Unlink",
                                        "bindingToken",
                                        f.token,
                                        "target",
                                        Map.of("issuer", f.http.issuer, "subject", "owner"))));
        assertEquals(
                "COMPLETED",
                f.poll("/identity/requests/" + unlink + "/result", "owner", null)
                        .get("outcome")
                        .asText());
        assertTrue(link(id).get("link").asText().endsWith(secret));
        f.register("owner");
        assertTrue(link(id).get("link").asText().endsWith(secret));
    }

    @Test
    void finalNonCreatorDeparturePreservesOtherPotAndHistoricalReads() throws Exception {
        startPot();
        String guest = binding("guest");
        String secret = invitationToken(create(Instant.parse("2030-01-01T00:00:00Z")));
        var attached = attach("guest", guest, secret, f.share, "join");
        assertEquals("COMPLETED", attached.get("outcome").asText());
        f.version = attached.get("resultingPotVersion").asLong();
        f.execute(
                "CreateExpense",
                Map.of(
                        "id",
                        f.expense.toString(),
                        "label",
                        "Kept",
                        "date",
                        "2026-11-05",
                        "amount",
                        f.fraction(5, 1),
                        "payerId",
                        f.share.toString(),
                        "participants",
                        List.of(f.participant(f.share))));
        long history = f.version;
        UUID otherPot = UUID.randomUUID(), otherShare = UUID.randomUUID();
        actorCommand("guest", guest, otherPot, 0, "CreatePot", Map.of("label", "Other Pot"));
        actorCommand(
                "guest",
                guest,
                otherPot,
                1,
                "AddShareholders",
                Map.of(
                        "targets",
                        List.of(
                                Map.of(
                                        "id",
                                        otherShare.toString(),
                                        "name",
                                        "Guest",
                                        "attachment",
                                        "SELF",
                                        "weight",
                                        f.fraction(1, 1)))));
        var identity =
                f.worker.write.queryForList("select * from v3_eui_version order by eui_id,version");
        var expense =
                f.worker.write.queryForList(
                        "select * from v3_expense_financials order by revision_id");
        var other =
                f.worker.write.queryForList(
                        "select * from v3_shareholder_details where pot_id=? order by revision_id",
                        otherPot);
        var departed =
                actorCommand(
                        "guest",
                        guest,
                        f.pot,
                        history,
                        "UpdateShareholderDetails",
                        Map.of(
                                "targets",
                                List.of(
                                        Map.of(
                                                "id",
                                                f.share.toString(),
                                                "name",
                                                "Free",
                                                "attachment",
                                                "DETACH"))));
        long after = departed.get("resultingPotVersion").asLong();
        assertEquals(history + 1, after);
        assertEquals(
                0,
                f.worker.write.queryForObject(
                        "select count(*) from v3_shareholder_details where pot_id=? and active_to"
                                + " is null and user_id is not null",
                        Integer.class,
                        f.pot));
        assertFalse(
                f.worker.write.queryForObject(
                        "select deleted from v3_shareholder_header where object_id=? and active_to"
                                + " is null",
                        Boolean.class,
                        f.share));
        assertEquals(
                identity,
                f.worker.write.queryForList(
                        "select * from v3_eui_version order by eui_id,version"));
        assertEquals(
                expense,
                f.worker.write.queryForList(
                        "select * from v3_expense_financials order by revision_id"));
        assertEquals(
                other,
                f.worker.write.queryForList(
                        "select * from v3_shareholder_details where pot_id=? order by revision_id",
                        otherPot));
        ReadMonolithI8Fixture.await(
                () ->
                        f.worker.read.queryForObject(
                                "select coalesce(max(pot_version),0)>=? from read_current_pot where"
                                        + " pot_id=?",
                                Boolean.class,
                                after,
                                f.pot));
        assertEquals(
                404,
                f.http.request("GET", "/pots/" + f.pot, f.http.jwt("guest"), guest, null, Map.of())
                        .statusCode());
        assertEquals(
                200,
                f.http.request(
                                "GET",
                                "/pots/" + f.pot + "/versions/" + history,
                                f.http.jwt("guest"),
                                guest,
                                null,
                                Map.of())
                        .statusCode());
        assertEquals(
                200,
                f.http.request(
                                "GET",
                                "/pots/" + f.pot,
                                f.http.jwt("owner"),
                                f.token,
                                null,
                                Map.of())
                        .statusCode());
        assertEquals(
                200,
                f.http.request(
                                "GET",
                                "/pots/" + otherPot,
                                f.http.jwt("guest"),
                                guest,
                                null,
                                Map.of())
                        .statusCode());
    }

    com.fasterxml.jackson.databind.JsonNode actorCommand(
            String actor, String binding, UUID pot, long observed, String op, Object payload)
            throws Exception {
        UUID id =
                f.accepted(
                        f.post(
                                "/commands",
                                actor,
                                UUID.randomUUID().toString(),
                                Map.of(
                                        "operation",
                                        op,
                                        "potId",
                                        pot.toString(),
                                        "observedVersion",
                                        observed,
                                        "bindingToken",
                                        binding,
                                        "payload",
                                        payload)));
        var result = f.poll("/commands/" + id + "/result", actor, null);
        assertEquals("COMPLETED", result.get("outcome").asText());
        return result;
    }

    @Test
    void brunoB4StandaloneCorpus() throws Exception {
        startRoots();
        var repo = java.nio.file.Path.of("../..").toAbsolutePath().normalize();
        var output =
                java.nio.file.Path.of(
                                System.getProperty("pocoma.verification.reports", "target/reports"))
                        .getParent()
                        .resolve("bruno-b4");
        java.nio.file.Files.createDirectories(output);
        var inputs = V3AdmissionFlowPostgresTest.brunoInputs(repo);
        var command =
                List.of(
                        repo.resolve("scripts/bruno-v3/node_modules/.bin/bru").toString(),
                        "run",
                        "-r",
                        "04 Invitations",
                        "--env",
                        "Local",
                        "--sandbox",
                        "developer",
                        "--noproxy",
                        "--reporter-json",
                        output.resolve("bruno.json").toString(),
                        "--reporter-skip-all-headers",
                        "--reporter-skip-body");
        var process =
                new ProcessBuilder(command).directory(repo.resolve("scripts/bruno-v3").toFile());
        var env = process.environment();
        String owner = f.http.jwt("owner"),
                guest = f.http.jwt("guest"),
                second = f.http.jwt("second");
        env.put("POCOMA_I4_BASE_URL", f.http.baseUrl);
        env.put("POCOMA_I4_BEARER_OWNER", owner);
        env.put("POCOMA_I4_BEARER_GUEST", guest);
        env.put("POCOMA_I4_BEARER_SECOND", second);
        env.put(
                "POCOMA_I4_HELPER",
                repo.resolve("scripts/bruno-v3/lib/i4-invitation.js").toString());
        env.put("POCOMA_I4_RECEIPTS", output.resolve("requests.jsonl").toString());
        var running =
                process.redirectErrorStream(true)
                        .redirectOutput(output.resolve("bruno.log").toFile())
                        .start();
        boolean finished = running.waitFor(180, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            running.destroyForcibly();
            running.waitFor();
        }
        assertTrue(finished, "B4 external process deadline");
        assertEquals(0, running.exitValue(), "B4 failed: inspect sanitized Bruno output");
        var scenarios = new ArrayList<String>();
        int requests = 0;
        for (String line : java.nio.file.Files.readAllLines(output.resolve("requests.jsonl"))) {
            var receipt = f.json.readTree(line);
            assertEquals("PASS", receipt.get("verdict").asText());
            scenarios.add(receipt.get("scenario").asText());
            requests += receipt.get("requests").size();
        }
        assertEquals(List.of("B401", "B402", "B403", "B404"), scenarios);
        assertEquals(
                3,
                f.worker.write.queryForObject("select count(*) from v3_invitation", Integer.class));
        assertEquals(
                1,
                f.worker.write.queryForObject(
                        "select count(*) from v3_command_result where"
                                + " rejection='INVALID_INVITATION'",
                        Integer.class));
        String diagnostics =
                java.nio.file.Files.readString(output.resolve("bruno.log")) + PG.getLogs();
        for (String bearer : List.of(owner, guest, second))
            assertFalse(diagnostics.contains(bearer), "Bearer leaked in observed logs");
        var crypto =
                com.kartaguez.pocoma.runtime.consumption.v3.V3InvitationConfiguration.keys(
                        f.http.context.getEnvironment());
        var ds = f.worker.write.getDataSource();
        var read = V3InvitationReadPostgresTest.owner(ds);
        for (UUID event :
                f.worker.write.queryForList(
                        "select event_id from v3_invitation_event", UUID.class)) {
            var invitation =
                    read.read(
                            c ->
                                    new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3
                                                    .JdbcInvitationSource(ds)
                                            .load(event, c));
            String secret = crypto.decrypt(invitation).substring("pocoma://invitation/".length());
            assertFalse(diagnostics.contains(secret), "Invitation leaked in observed logs");
        }
        assertEquals(
                inputs,
                V3AdmissionFlowPostgresTest.brunoInputs(repo),
                "Bruno inputs changed during execution");
        java.nio.file.Files.writeString(
                output.resolve("receipt.json"),
                f.json.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(
                                Map.of(
                                        "scenarios",
                                        scenarios,
                                        "helperRequests",
                                        requests,
                                        "primaryRequests",
                                        4,
                                        "authentication",
                                        "three locally signed JWT sessions, real Resource Server"
                                                + " and JWK HTTP boundary",
                                        "externalIdpCertified",
                                        false)));
    }

    static Set<String> fieldNames(com.fasterxml.jackson.databind.JsonNode n) {
        var names = new HashSet<String>();
        n.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
