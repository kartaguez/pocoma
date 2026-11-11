package com.kartaguez.pocoma.supra.web.read.v3;
import com.kartaguez.pocoma.contract.readquery.InvitationRead;
import com.kartaguez.pocoma.contract.readquery.ReadPotV3Result;

import com.kartaguez.pocoma.contract.authentication.AuthenticatedExternalPrincipal;

import jakarta.servlet.http.*;

import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.*;

@org.springframework.context.annotation.Profile("v3-http-read")
@RestController
public final class V3InvitationController {
    private final InvitationRead.Queries queries;
    private final V3ReadTransport transport;

    public V3InvitationController(InvitationRead.Queries queries, V3ReadTransport transport) {
        this.queries = queries;
        this.transport = transport;
    }

    @GetMapping("/api/v3/invitations/by-creation-request/{requestId}")
    public void link(
            @PathVariable String requestId,
            AuthenticatedExternalPrincipal p,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        V3ReadTransport.require(request.getQueryString() == null);
        UUID id;
        try {
            id = UUID.fromString(requestId);
            V3ReadTransport.require(id.toString().equalsIgnoreCase(requestId));
        } catch (IllegalArgumentException invalid) {
            throw new V3ReadTransport.Failure(400);
        }
        var result = queries.link(p.identity(), id);
        if (result instanceof ReadPotV3Result.Available<InvitationRead.Link> a) {
            var v = a.value();
            transport.write(
                    response,
                    Map.of(
                            "invitationId",
                            v.invitationId(),
                            "potId",
                            v.potId(),
                            "expiresAt",
                            v.expiresAt().toString(),
                            "link",
                            v.link()));
        } else error(result);
    }

    @PostMapping("/api/v3/invitations/available-shareholders/query")
    public void places(
            AuthenticatedExternalPrincipal p,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        V3ReadTransport.require(request.getQueryString() == null);
        try {
            var n =
                    transport
                            .mapper()
                            .readTree((byte[]) request.getAttribute(V3ReadTransport.BODY));
            V3ReadTransport.require(
                    n != null
                            && n.isObject()
                            && n.size() == 1
                            && n.has("token")
                            && n.get("token").isTextual()
                            && !n.get("token").asText().isBlank());
            var bindings = Collections.list(request.getHeaders("X-Pocoma-Binding-Token"));
            V3ReadTransport.require(bindings.size() == 1 && !bindings.getFirst().isBlank());
            var result = queries.places(p.identity(), bindings.getFirst(), n.get("token").asText());
            if (result instanceof ReadPotV3Result.Available<List<InvitationRead.Place>> a)
                transport.write(response, Map.of("shareholders", a.value()));
            else error(result);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new V3ReadTransport.Failure(400);
        }
    }

    private static void error(ReadPotV3Result<?> result) {
        throw new V3ReadTransport.Failure(
                result instanceof ReadPotV3Result.NotFound<?>
                        ? 404
                        : result instanceof ReadPotV3Result.InvalidInput<?> ? 400 : 503);
    }

    @ExceptionHandler(Exception.class)
    public void failure(Exception failure, HttpServletResponse response) throws IOException {
        if (!response.isCommitted())
            V3ReadTransport.error(
                    response,
                    failure instanceof V3ReadTransport.Failure f ? f.status : 503);
    }
}
