package com.kartaguez.pocoma.supra.consume.registrationresult;
import com.kartaguez.pocoma.engine.materialize.registrationresult.RegistrationResultDiscovery;

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
import com.kartaguez.pocoma.engine.materialize.registrationresult.MaterializeRegistrationResultService;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionSearch;
import com.kartaguez.pocoma.orchestrator.consumption.locator.LocatedConsumption;

public final class RegistrationResultConsumptionLocator implements ConsumptionLocator {
    private final int segmentIndex, segmentCount;
    private final RegistrationResultDiscovery discovery;
    private final MaterializeRegistrationResultService materializer;
    private final Clock clock;
    public RegistrationResultConsumptionLocator(int segmentIndex, int segmentCount,
            RegistrationResultDiscovery discovery, MaterializeRegistrationResultService materializer, Clock clock) {
        this.segmentIndex = segmentIndex; this.segmentCount = segmentCount;
        this.discovery = discovery; this.materializer = materializer; this.clock = clock;
    }
    @Override public ConsumptionSearch openSearch() {
        return new ConsumptionSearch() {
            private Optional<RegistrationResultDiscovery.Cursor> cursor = Optional.empty();
            private final java.time.Instant now = clock.instant();
            @Override public Optional<LocatedConsumption> next() {
                var candidate = discovery.next(segmentIndex, segmentCount, now, cursor);
                if (candidate.isEmpty()) return Optional.empty();
                var found = candidate.orElseThrow(); cursor = Optional.of(found.cursor());
                return Optional.of(new LocatedConsumption(key(found.requestId()), context -> {
                    materializer.materialize(found.requestId());
                    return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(),
                            List.of(new ConsumptionInput(context.slotId(), "REGISTRATION_OUTCOME",
                                    found.requestId().toString(), 1)), List.of());
                }, failure -> new ProcessingFailure(new ProcessingFailureCode("REGISTRATION_RESULT_MATERIALIZATION"),
                        failure instanceof IllegalStateException ? "REGISTRATION_RESULT_INVARIANT" : "REGISTRATION_RESULT_TECHNICAL",
                        String.valueOf(failure.getMessage()), clock.instant())));
            }
        };
    }
    public static ConsumptionFailurePolicy failurePolicy() {
        return context -> context.failure().category().equals("REGISTRATION_RESULT_INVARIANT")
                ? new FailureDecision.Fail() : new FailureDecision.RetryAfter(Duration.ofSeconds(5));
    }
    public static ConsumptionKey key(UUID requestId) {
        return new ConsumptionKey(new ConsumableIdentity("REGISTRATION_OUTCOME", List.of(requestId.toString())),
                new ConsumerIdentity(RegistrationResultDiscovery.CONSUMER_TYPE, List.of()));
    }
}
