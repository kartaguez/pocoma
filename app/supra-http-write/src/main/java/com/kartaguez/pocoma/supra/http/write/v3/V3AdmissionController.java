package com.kartaguez.pocoma.supra.http.write.v3;

import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.contracts.command.v3.AdmissionFailure;
import com.kartaguez.pocoma.engine.admit.command.v3.*;
import com.kartaguez.pocoma.engine.admit.registration.v3.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand;

import jakarta.servlet.http.*;

import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@org.springframework.context.annotation.Profile("v3-http-admission")
@RestController
public final class V3AdmissionController {
    @FunctionalInterface
    public interface AdmissionListener {
        void onCommandAdmitted(UUID commandId);
    }

    public static volatile AdmissionListener ON_ADMITTED = null;

    private final AdmitCommandV3 commands;
    private final AdmitIdentityRequestV3 identity;
    private final V3AdmissionTransport transport;

    public V3AdmissionController(
            AdmitCommandV3 commands,
            AdmitIdentityRequestV3 identity,
            V3AdmissionTransport transport) {
        this.commands = commands;
        this.identity = identity;
        this.transport = transport;
    }

    @PostMapping("/api/v3/commands")
    public void command(
            AuthenticatedExternalPrincipal p,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        try {
            String key = key(request);
            var input =
                    CommandAdmissionInput.decode(
                            transport.parse(body(request)),
                            new PotCommand.Eui(p.issuer(), p.subject()),
                            transport.limits().rationalDigits());
            UUID id = commands.admit(p, key, input);
            AdmissionListener staticHook = ON_ADMITTED;
            if (staticHook != null) staticHook.onCommandAdmitted(id);
            accepted(response, id, "/api/v3/commands/");
        } catch (AdmissionFailure failure) {
            if (!failure.physicalTerminationObserved())
                request.setAttribute(V3AdmissionTransport.UNRESOLVED, true);
            V3AdmissionTransport.error(response, failure.status());
        } catch (RuntimeException failure) {
            V3AdmissionTransport.error(response, 503);
        }
    }

    @PostMapping("/api/v3/identity/requests")
    public void identity(
            AuthenticatedExternalPrincipal p,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        try {
            String key = key(request);
            var input = IdentityAdmissionInput.decode(transport.parse(body(request)), p.identity());
            accepted(response, identity.admit(p, key, input), "/api/v3/identity/requests/");
        } catch (AdmissionFailure failure) {
            if (!failure.physicalTerminationObserved())
                request.setAttribute(V3AdmissionTransport.UNRESOLVED, true);
            V3AdmissionTransport.error(response, failure.status());
        } catch (RuntimeException failure) {
            V3AdmissionTransport.error(response, 503);
        }
    }

    private static byte[] body(HttpServletRequest request) {
        if (!(request.getAttribute(V3AdmissionTransport.BODY) instanceof byte[] body))
            throw new AdmissionFailure(503);
        return body;
    }

    private static String key(HttpServletRequest request) {
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        AdmissionFailure.require(
                keys.size() == 1 && keys.getFirst().matches("[A-Za-z0-9._~:-]{1,128}"));
        return keys.getFirst();
    }

    private static void accepted(HttpServletResponse response, UUID id, String prefix)
            throws IOException {
        byte[] body = ("{\"requestId\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8);
        response.setStatus(202);
        response.setHeader("Location", prefix + id + "/result");
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/json");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }
}
