package com.kartaguez.pocoma.supra.web.write.v3;
import com.kartaguez.pocoma.contract.writeadmission.LinkProposalInput;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.core.JsonGenerator;
import com.kartaguez.pocoma.contract.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionFailure;
import com.kartaguez.pocoma.contract.writeadmission.*;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.*;

@org.springframework.context.annotation.Profile("v3-http-admission")
@RestController
public final class V3LinkProposalController {
    private final IssueLinkProposal links;
    private final V3AdmissionTransport transport;
    private final ObjectMapper json = new ObjectMapper().registerModule(new SimpleModule()
            .addSerializer(java.time.Instant.class, new JsonSerializer<java.time.Instant>() {
                @Override public void serialize(java.time.Instant value, JsonGenerator output,
                        SerializerProvider provider) throws IOException { output.writeString(value.toString()); }
            }));
    public V3LinkProposalController(IssueLinkProposal links, V3AdmissionTransport transport) {
        this.links = links; this.transport = transport;
    }
    @PostMapping("/api/v3/identity/link-requests")
    public void create(AuthenticatedExternalPrincipal actor, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        handle(request, response, () -> {
            var keys = Collections.list(request.getHeaders("Idempotency-Key"));
            AdmissionFailure.require(keys.size() == 1 && keys.getFirst().matches("[A-Za-z0-9._~:-]{1,128}"));
            var input = LinkProposalInput.decode(transport.parse(body(request)), actor.identity());
            var result = links.create(actor, keys.getFirst(), input);
            write(response, 201, result, result.linkRequestId());
        });
    }
    @GetMapping("/api/v3/identity/link-requests/{linkRequestId}")
    public void get(AuthenticatedExternalPrincipal actor, @PathVariable String linkRequestId,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        handle(request, response, () -> {
            AdmissionFailure.require(body(request).length == 0);
            write(response, 200, links.get(actor, id(linkRequestId)), null);
        });
    }
    @PostMapping("/api/v3/identity/link-requests/{linkRequestId}/accept")
    public void accept(AuthenticatedExternalPrincipal actor, @PathVariable String linkRequestId,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        handle(request, response, () -> {
            AdmissionFailure.require(body(request).length == 0 && request.getHeader("Idempotency-Key") == null);
            var result = links.accept(actor, id(linkRequestId));
            write(response, 202, result, result.linkRequestId());
        });
    }
    private static UUID id(String value) {
        AdmissionFailure.require(value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"));
        return UUID.fromString(value);
    }
    private static byte[] body(HttpServletRequest request) {
        if (!(request.getAttribute(V3AdmissionTransport.BODY) instanceof byte[] value)) throw new AdmissionFailure(503);
        return value;
    }
    @FunctionalInterface private interface Action { void run() throws IOException; }
    private void handle(HttpServletRequest request, HttpServletResponse response, Action action) throws IOException {
        try { action.run(); }
        catch (AdmissionFailure failure) {
            if (!failure.physicalTerminationObserved()) request.setAttribute(V3AdmissionTransport.UNRESOLVED, true);
            V3AdmissionTransport.error(response, failure.status());
        } catch (RuntimeException failure) { V3AdmissionTransport.error(response, 503); }
    }
    private void write(HttpServletResponse response, int status, Object value, UUID location) throws IOException {
        byte[] bytes = json.writeValueAsBytes(value);
        if (bytes.length > 65_536) throw new AdmissionFailure(503);
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        if (location != null) response.setHeader("Location", "/api/v3/identity/link-requests/" + location);
        response.setContentType("application/json"); response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes); response.flushBuffer();
    }
}
