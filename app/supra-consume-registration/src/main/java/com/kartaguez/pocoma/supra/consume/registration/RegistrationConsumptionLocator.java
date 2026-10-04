package com.kartaguez.pocoma.supra.consume.registration;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.ConsumptionFailurePolicy;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.consume.registration.ExecuteRegistrationService;
import com.kartaguez.pocoma.engine.consume.registration.RegistrationDiscoveryPort;
import com.kartaguez.pocoma.engine.consume.registration.RegistrationDiscoveryPort.Cursor;
import com.kartaguez.pocoma.contracts.registration.RegistrationOutcome;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionSearch;
import com.kartaguez.pocoma.orchestrator.consumption.locator.LocatedConsumption;

public final class RegistrationConsumptionLocator implements ConsumptionLocator {
    private final int segmentIndex, segmentCount;
    private final RegistrationDiscoveryPort discovery;
    private final ExecuteRegistrationService registration;
    private final Clock clock;

    public RegistrationConsumptionLocator(int segmentIndex, int segmentCount,
            RegistrationDiscoveryPort discovery, ExecuteRegistrationService registration, Clock clock) {
        this.segmentIndex = segmentIndex; this.segmentCount = segmentCount;
        this.discovery = discovery; this.registration = registration; this.clock = clock;
    }

    @Override public ConsumptionSearch openSearch() {
        return new ConsumptionSearch() {
            private Optional<Cursor> cursor = Optional.empty();
            private final java.time.Instant now = clock.instant();
            @Override public Optional<LocatedConsumption> next() {
                var candidate = discovery.next(segmentIndex, segmentCount, now, cursor);
                if (candidate.isEmpty()) return Optional.empty();
                var found = candidate.orElseThrow(); cursor = Optional.of(found.cursor());
                return Optional.of(new LocatedConsumption(key(found.requestId()), context -> {
                    RegistrationOutcome outcome = registration.execute(found.requestId());
                    var business = outcome instanceof RegistrationOutcome.Rejected
                            ? new BusinessConsumptionOutcome.Rejected(RegistrationOutcome.Rejected.CODE)
                            : new BusinessConsumptionOutcome.Success();
                    return new ConsumptionExecutionResult(business,
                            List.of(new ConsumptionInput(context.slotId(), "REGISTRATION_REQUEST",
                                    found.requestId().toString(), 1)), List.of());
                }, failure -> new ProcessingFailure(new ProcessingFailureCode("REGISTRATION_EXECUTION"),
                        failure instanceof IllegalStateException || failure instanceof IllegalArgumentException
                                ? "REGISTRATION_INVARIANT" : "REGISTRATION_TECHNICAL",
                        String.valueOf(failure.getMessage()), clock.instant())));
            }
        };
    }

    public static ConsumptionFailurePolicy failurePolicy() {
        return context -> context.failure().category().equals("REGISTRATION_INVARIANT")
                ? new FailureDecision.Fail() : new FailureDecision.RetryAfter(Duration.ofSeconds(5));
    }

    public static ConsumptionKey key(UUID requestId) {
        return new ConsumptionKey(new ConsumableIdentity("REGISTRATION_REQUEST", List.of(requestId.toString())),
                new ConsumerIdentity("REGISTRATION_WORKER_V1", List.of()));
    }
}
