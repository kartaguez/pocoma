package com.kartaguez.pocoma.supra.http.write;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.engine.admit.registration.AdmitRegistrationService;

@RestController
@RequestMapping("/api/v1/registrations")
@ConditionalOnProperty(prefix = "pocoma.registration-admission", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class RegistrationController {
    private final AdmitRegistrationService admission;
    private final ObjectMapper mapper;
    public RegistrationController(AdmitRegistrationService admission,
            @Qualifier("webApiObjectMapper") ObjectMapper mapper) {
        this.admission = admission;
        this.mapper = mapper;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AcceptedRegistrationResponse submit(@RequestBody(required = false) String payload,
            AuthenticatedExternalPrincipal principal) {
        try {
            if (payload != null && !payload.isBlank()) {
                var parsed = mapper.readTree(payload);
                if (!parsed.isObject() || !parsed.isEmpty()) throw new IllegalArgumentException("Registration payload must be an empty object");
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid Registration payload", exception);
        }
        return new AcceptedRegistrationResponse(admission.admit(principal));
    }

    public record AcceptedRegistrationResponse(UUID requestId) {}
}
