package com.kartaguez.pocoma.supra.http.read.query;

import static java.util.Objects.requireNonNull;

import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.engine.read.currentbinding.GetCurrentBindingUseCase;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v1/me/binding")
@ConditionalOnProperty(prefix = "pocoma.command-result-read", name = "enabled", havingValue = "true")
@Tag(name = "Current binding")
@SecurityRequirement(name = "bearerAuth")
public final class CurrentBindingController {
	private final GetCurrentBindingUseCase bindings;

	public CurrentBindingController(GetCurrentBindingUseCase bindings) {
		this.bindings = requireNonNull(bindings, "bindings must not be null");
	}

	@GetMapping
	@Operation(summary = "Read the authenticated external identity's current binding")
	public ResponseEntity<CurrentBindingResponse> get(AuthenticatedExternalPrincipal principal) {
		return bindings.getAttached(principal.identity())
				.map(binding -> ResponseEntity.ok(new CurrentBindingResponse(
						binding.userId().value(), binding.bindingId().value(),
						binding.bindingRevision().value(), binding.status().name())))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}
}
