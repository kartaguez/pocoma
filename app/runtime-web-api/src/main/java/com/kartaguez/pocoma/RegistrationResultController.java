package com.kartaguez.pocoma;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kartaguez.pocoma.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.engine.registration.GetRegistrationResultService;
import com.kartaguez.pocoma.contracts.registration.RegistrationOutcome;

@RestController
@RequestMapping("/api/v1/registrations")
public final class RegistrationResultController {
    private final GetRegistrationResultService results;
    public RegistrationResultController(GetRegistrationResultService results) { this.results = results; }

    @GetMapping("/{requestId}/result")
    public ResponseEntity<RegistrationResultResponse> get(@PathVariable UUID requestId,
            AuthenticatedExternalPrincipal principal) {
        return results.get(requestId, principal.identity()).map(outcome -> {
            if (outcome instanceof RegistrationOutcome.Registered registered) {
                return ResponseEntity.ok(new RegistrationResultResponse(requestId, "REGISTERED",
                        registered.userId().value(), registered.bindingId().value(), null));
            }
            return ResponseEntity.ok(new RegistrationResultResponse(requestId, "REJECTED", null, null,
                    RegistrationOutcome.Rejected.CODE));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record RegistrationResultResponse(UUID requestId, String status, UUID userId, UUID bindingId, String code) {}
}
