package com.kartaguez.pocoma.supra.http.write.v3;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.HmacAdmissionProtection;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionAssociation;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionFailure;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionProtection;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.contract.writeadmission.*;
import com.kartaguez.pocoma.engine.admit.command.v3.*;
import com.kartaguez.pocoma.engine.admit.registration.v3.AdmitIdentityRequestV3;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;

/**
 * No Spring context or database: real filter/controller propagation of the physical owner verdict.
 */
class V3AdmissionTransportTest {
    void physical(boolean observed) throws Exception {
        var transport = new V3AdmissionTransport(V3AdmissionTransport.Limits.initial());
        var port =
                new AdmitCommandV3.Store() {
                    public Optional<AdmissionAssociation> find(byte[] key) {
                        return Optional.empty();
                    }

                    public boolean reserve(AdmissionAssociation association) {
                        throw new AssertionError();
                    }

                    public void insert(PotCommand command) {
                        throw new AssertionError();
                    }
                };
        var tx =
                new AttemptTransactions() {
                    public <T> T read(Function<RecoveryReadContext, T> action, Guard guard) {
                        return action.apply(() -> {});
                    }

                    public <T> T write(Supplier<T> action, Guard guard) {
                        throw new AdmissionFailure(503, observed);
                    }

                    public void poison(Throwable t) {}

                    public void requirePublicationClosed() {}
                };
        var service =
                new AdmitCommandV3(
                        port,
                        tx,
                        new HmacAdmissionProtection(
                                "stable", new byte[32], "k", Map.of("k", new byte[32])),
                        Clock.systemUTC(),
                        4096);
        var controller =
                new V3AdmissionController(service, (AdmitIdentityRequestV3) null, transport);
        var now = Instant.now();
        var principal =
                new AuthenticatedExternalPrincipal(
                        "i", "s", now, now, now.plusSeconds(60), Set.of());
        var calls = new AtomicInteger();
        for (int n = 0; n < 2; n++) {
            var request = new MockHttpServletRequest("POST", "/api/v3/commands");
            request.setContentType("application/json");
            request.addHeader("Idempotency-Key", "key");
            request.setContent(
                    "{\"operation\":\"CreatePot\",\"potId\":\"00000000-0000-0000-0000-000000000001\",\"observedVersion\":0,\"bindingToken\":\"token\",\"payload\":{\"label\":\"A\"}}"
                            .getBytes(StandardCharsets.UTF_8));
            var response = new MockHttpServletResponse();
            transport.doFilter(
                    request,
                    response,
                    (r, s) -> {
                        calls.incrementAndGet();
                        controller.command(
                                principal,
                                (jakarta.servlet.http.HttpServletRequest) r,
                                (jakarta.servlet.http.HttpServletResponse) s);
                    });
            assertEquals(503, response.getStatus());
            assertEquals(observed ? 1 : 0, transport.availableCapacity());
        }
        assertEquals(observed ? 2 : 1, calls.get());
    }

    @Test
    void unresolvedPhysicalTerminationRetainsCapacity() throws Exception {
        physical(false);
    }

    @Test
    void observedPhysicalTerminationReleasesCapacity() throws Exception {
        physical(true);
    }
}
