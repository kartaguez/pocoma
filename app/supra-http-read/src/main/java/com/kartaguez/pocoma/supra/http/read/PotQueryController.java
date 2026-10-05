package com.kartaguez.pocoma.supra.http.read;

import static java.util.Objects.requireNonNull;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.read.pot.ReadPotResult;
import com.kartaguez.pocoma.engine.read.pot.ReadPotForExternalIdentityUseCase;
import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/pots")
@ConditionalOnProperty(prefix = "pocoma.pot-read", name = "enabled", havingValue = "true")
@Tag(name = "Pot queries")
@SecurityRequirement(name = "bearerAuth")
public final class PotQueryController {
	private final ReadPotForExternalIdentityUseCase pots;

    public PotQueryController(ReadPotForExternalIdentityUseCase pots) {
        this.pots = requireNonNull(pots, "pots must not be null");
    }

	@GetMapping("/{potId}")
	@Operation(summary = "Read an authorized Pot at an exact business version")
	public ResponseEntity<?> get(@PathVariable UUID potId, @RequestParam long version,
			AuthenticatedExternalPrincipal principal, HttpServletRequest request) {
		if (version < 1) return error("INVALID_VERSION", "version must be greater than or equal to 1",
				HttpStatus.BAD_REQUEST, request);
		ReadPotResult result = pots.read(principal.identity(), principal.externalAuthorities(), new PotId(potId), version);
		if (result instanceof ReadPotResult.Ready ready) return ResponseEntity.ok(PotResponse.from(ready.pot()));
		if (result instanceof ReadPotResult.Forbidden) return ResponseEntity.notFound().build();
		if (result instanceof ReadPotResult.ReadPotFailed) {
			return error("POT_PROJECTION_FAILED", "Pot projection failed", HttpStatus.SERVICE_UNAVAILABLE, request);
		}
		return error("POT_NOT_READY", "Pot is not ready", HttpStatus.CONFLICT, request);
	}

	private static ResponseEntity<ReadErrorResponse> error(String code, String message, HttpStatus status,
			HttpServletRequest request) {
		return ResponseEntity.status(status)
				.body(new ReadErrorResponse(code, message, status.value(), request.getRequestURI()));
	}
}
