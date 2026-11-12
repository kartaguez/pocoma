package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.kartaguez.pocoma.infra.observability.micrometer.v3.StageHandoffTracker;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class V3StageHandoffsHttpTest {
    @Container
    static final PostgreSQLContainer PG = V3ReadHttpFixture.container();

    @Test
    void bothRoutesRedactPopulatedRecordsAndRefuseReset() throws Exception {
        try (var fixture = new V3ReadHttpFixture(PG)) {
            fixture.start(PocomaWebReadApplication.class);
            var tracker = fixture.context.getBean("webReadHandoffs", StageHandoffTracker.class);
            UUID command = UUID.randomUUID();
            UUID pot = UUID.randomUUID();
            UUID event = UUID.randomUUID();
            tracker.onCommandAdmitted(command);
            tracker.linkCommandEvents(command, pot, 2L, event, UUID.randomUUID());
            var projection = com.kartaguez.pocoma.domain.consumption.v3.ConsumptionCatalog
                    .definitions().stream().filter(d -> d.family().name().equals("POT_EVENT"))
                    .findFirst().orElseThrow();
            tracker.onAttemptStarting(projection.id(), event, System.nanoTime());
            tracker.onCommandAdmitted(UUID.randomUUID());
            var api = "/api/v3/diagnostics/stage-handoffs";
            assertEquals(401, get(fixture, api, null).statusCode());
            assertEquals(401, get(fixture, api, "bad-token").statusCode());

            var bearer = fixture.jwt("other");
            for (String path : List.of(api, "/actuator/stage-handoffs")) {
                String auth = path.equals(api) ? bearer : null;
                var result = get(fixture, path + "?limit=1", auth);
                assertEquals(200, result.statusCode());
                assertEquals("no-store", result.headers().firstValue("Cache-Control").orElseThrow());
                var body = fixture.body(result);
                assertEquals(1, body.path("records").size());
                assertEquals(2, body.path("totalTracked").asInt());
                assertEquals(4, body.path("partitionStats").size());
                assertEquals(0, body.path("eventProjections").size());
                for (UUID id : List.of(command, pot, event)) {
                    assertFalse(body.toString().contains(id.toString()));
                }
                assertEquals(400, get(fixture, path + "?clear=true", auth).statusCode());
                assertEquals(2, fixture.body(get(fixture, path, auth)).path("totalTracked").asInt());
            }
        }
    }

    private HttpResponse<byte[]> get(V3ReadHttpFixture fixture, String path, String bearer)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(fixture.baseUrl + path));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return fixture.client.send(request.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
