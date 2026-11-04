package com.kartaguez.pocoma.supra.http.read.v3;

import static com.kartaguez.pocoma.supra.http.read.v3.V3ReadTransport.require;
import com.fasterxml.jackson.databind.JsonNode;
import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.read.pot.v3.*;
import com.kartaguez.pocoma.engine.read.currentbinding.v3.GetCurrentBindingV3;
import com.kartaguez.pocoma.engine.read.commandresult.v3.GetCommandTerminalResult;
import com.kartaguez.pocoma.engine.read.registrationresult.v3.GetIdentityTerminalResult;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;

@RestController
@RequestMapping("/api/v3")
public final class V3ReadController {
    private final ReadPotV3.Queries pots;
    private final GetCurrentBindingV3.Query bindings;
    private final GetCommandTerminalResult.Query commands;
    private final GetIdentityTerminalResult.Query identities;
    private final V3ReadTransport transport;
    public V3ReadController(ReadPotV3.Queries pots, GetCurrentBindingV3.Query bindings,
            GetCommandTerminalResult.Query commands, GetIdentityTerminalResult.Query identities, V3ReadTransport transport) {
        this.pots = pots; this.bindings = bindings; this.commands = commands; this.identities = identities; this.transport = transport;
    }
    @GetMapping("/me/binding")
    public void binding(AuthenticatedExternalPrincipal principal, HttpServletRequest request, HttpServletResponse response) throws IOException {
        query(request, "minVersion");
        var answer = bindings.get(eui(principal), number(request.getParameter("minVersion"), 0));
        switch (answer.status()) {
            case ABSENT -> throw new V3ReadTransport.Failure(404);
            case NOT_READY, UNAVAILABLE -> throw new V3ReadTransport.Failure(503);
            case AVAILABLE -> { var v = answer.value(); transport.write(response, new V3ReadDtos.Binding(v.euiVersion(), v.active(), v.bindingToken())); }
        }
    }
    @GetMapping("/pots/{potId}")
    public void current(@PathVariable String potId, AuthenticatedExternalPrincipal principal, HttpServletRequest request, HttpServletResponse response) throws IOException {
        query(request, "minVersion");
        send(response, pots.current(eui(principal), token(request), uuid(potId), number(request.getParameter("minVersion"), 0)), V3ReadDtos::pot);
    }
    @GetMapping("/pots/{potId}/versions/{version}")
    public void at(@PathVariable String potId, @PathVariable String version, AuthenticatedExternalPrincipal principal, HttpServletRequest request, HttpServletResponse response) throws IOException {
        query(request); send(response, pots.at(eui(principal), token(request), uuid(potId), positive(version)), V3ReadDtos::pot);
    }
    @GetMapping("/pots/{potId}/versions/{version}/balance")
    public void balance(@PathVariable String potId, @PathVariable String version, AuthenticatedExternalPrincipal principal, HttpServletRequest request, HttpServletResponse response) throws IOException {
        query(request); send(response, pots.balance(eui(principal), token(request), uuid(potId), positive(version)),
                (v, served) -> new V3ReadDtos.Balance(v.potId(), served, V3ReadDtos.balances(v.values())));
    }
    @PostMapping("/pots/{potId}/versions/{version}/expenses/query")
    public void expenses(@PathVariable String potId, @PathVariable String version, AuthenticatedExternalPrincipal principal, HttpServletRequest request, HttpServletResponse response) throws IOException {
        query(request); UUID id = uuid(potId); long v = positive(version);
        List<ReadPotV3Result.ExpenseRef> refs = references((byte[]) request.getAttribute(V3ReadTransport.BODY));
        send(response, pots.expenses(eui(principal), token(request), id, v, refs), (values, served) -> V3ReadDtos.batch(id, served, values));
    }
    @GetMapping("/me/pots")
    public void navigation(AuthenticatedExternalPrincipal principal, HttpServletRequest request, HttpServletResponse response) throws IOException {
        query(request, "limit", "cursor"); long limit = number(request.getParameter("limit"), 20); require(limit >= 1 && limit <= 100);
        UUID after = decodeCursor(request.getParameter("cursor"));
        send(response, pots.visible(eui(principal), token(request), after, (int) limit),
            (ids, v) -> new V3ReadDtos.Navigation(ids, ids.size() == limit ? encodeCursor(ids.getLast()) : null));
    }
    @GetMapping("/commands/{requestId}/result")
    public void command(@PathVariable String requestId, AuthenticatedExternalPrincipal principal, HttpServletRequest request, HttpServletResponse response) throws IOException {
        query(request); var value = commands.get(uuid(requestId), eui(principal)).orElseThrow(() -> new V3ReadTransport.Failure(404));
        transport.write(response, new V3ReadDtos.Command(value.requestId(), value.operation(), value.potId(), value.outcome(), value.resultingPotVersion(), value.rejection()));
    }
    @GetMapping("/identity/requests/{requestId}/result")
    public void identity(@PathVariable String requestId, AuthenticatedExternalPrincipal principal, HttpServletRequest request, HttpServletResponse response) throws IOException {
        query(request); var value = identities.get(uuid(requestId), eui(principal)).orElseThrow(() -> new V3ReadTransport.Failure(404));
        var target = value.targetEui();
        transport.write(response, new V3ReadDtos.Identity(value.requestId(), value.operation(), value.outcome(), target == null ? null : new V3ReadDtos.Eui(target.issuer(), target.subject()), value.targetVersion(), value.rejection()));
    }
    private <T> void send(HttpServletResponse response, ReadPotV3Result<T> result, BiFunction<T, Long, Object> dto) throws IOException {
        if (result instanceof ReadPotV3Result.Available<T> available) transport.write(response, dto.apply(available.value(), available.servedVersion()));
        else throw new V3ReadTransport.Failure(result instanceof ReadPotV3Result.InvalidInput<?> ? 400 : result instanceof ReadPotV3Result.NotFound<?> ? 404 : 503);
    }
    private static ExternalIdentity eui(AuthenticatedExternalPrincipal principal) { return new ExternalIdentity(principal.issuer(), principal.subject()); }
    public static UUID uuid(String value) {
        try { UUID result = UUID.fromString(value); require(result.toString().equals(value)); return result; }
        catch (IllegalArgumentException | NullPointerException malformed) { throw new V3ReadTransport.Failure(400); }
    }
    public static long number(String value, long fallback) {
        if (value == null) return fallback;
        require(value.matches("0|[1-9][0-9]{0,18}"));
        try { return Long.parseLong(value); } catch (NumberFormatException overflow) { throw new V3ReadTransport.Failure(400); }
    }
    private static long positive(String value) { long n = number(value, 0); require(n > 0); return n; }
    private static void query(HttpServletRequest request, String... allowed) {
        var names = Set.of(allowed);
        request.getParameterMap().forEach((key, values) -> require(names.contains(key) && values.length == 1));
    }
    private static String token(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("X-Pocoma-Binding-Token"));
        require(values.size() == 1); String value = values.getFirst();
        require(!value.isBlank() && value.chars().allMatch(c -> c >= 33 && c <= 126)); return value;
    }
    public static String encodeCursor(UUID id) { return Base64.getUrlEncoder().withoutPadding().encodeToString(("1:" + id).getBytes(StandardCharsets.UTF_8)); }
    public static UUID decodeCursor(String cursor) {
        if (cursor == null) return null;
        require(cursor.length() <= 256);
        try {
            String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            require(text.startsWith("1:")); UUID id = uuid(text.substring(2)); require(encodeCursor(id).equals(cursor)); return id;
        } catch (IllegalArgumentException malformed) { throw new V3ReadTransport.Failure(400); }
    }
    private List<ReadPotV3Result.ExpenseRef> references(byte[] body) {
        try {
            JsonNode root = transport.mapper().readTree(body); fields(root, Set.of("references"));
            var refs = root.get("references"); require(refs != null && refs.isArray() && !refs.isEmpty() && refs.size() <= 50);
            var result = new ArrayList<ReadPotV3Result.ExpenseRef>(); var ids = new HashSet<UUID>();
            for (JsonNode ref : refs) {
                fields(ref, Set.of("id", "headerVersion", "detailsVersion", "financialsVersion", "deleted"));
                require(ref.get("id").isTextual() && ref.get("deleted").isBoolean());
                UUID id = uuid(ref.get("id").textValue()); require(ids.add(id));
                result.add(new ReadPotV3Result.ExpenseRef(id, integer(ref.get("headerVersion")), integer(ref.get("detailsVersion")), integer(ref.get("financialsVersion")), ref.get("deleted").booleanValue()));
            }
            return List.copyOf(result);
        } catch (IOException | IllegalArgumentException | NullPointerException malformed) { throw new V3ReadTransport.Failure(400); }
    }
    private static void fields(JsonNode value, Set<String> names) {
        require(value != null && value.isObject() && value.size() == names.size()); value.fieldNames().forEachRemaining(n -> require(names.contains(n)));
    }
    private static long integer(JsonNode value) { require(value != null && value.isIntegralNumber() && value.canConvertToLong() && value.longValue() > 0); return value.longValue(); }
    @ExceptionHandler(Exception.class)
    public void failure(Exception failure, HttpServletResponse response) throws IOException {
        if (!response.isCommitted()) V3ReadTransport.error(response, failure instanceof V3ReadTransport.Failure f ? f.status : 503);
    }
}
