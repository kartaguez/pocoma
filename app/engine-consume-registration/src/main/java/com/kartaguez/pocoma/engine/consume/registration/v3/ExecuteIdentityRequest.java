package com.kartaguez.pocoma.engine.consume.registration.v3;

import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import java.util.*;
import java.util.function.Supplier;

/** One L2 participant, with decisions made only under final EUI protection. */
public final class ExecuteIdentityRequest implements ConsumptionParticipant {
    private final IdentityRequestStore store;
    private final IdentityRequestObservation observation;
    private final IdentityAuthority authority;
    private final Supplier<BindingToken> tokens;
    private final Supplier<UUID> userIds;

    public ExecuteIdentityRequest(
            IdentityRequestStore store,
            IdentityRequestObservation observation,
            IdentityAuthority authority,
            Supplier<BindingToken> tokens,
            Supplier<UUID> userIds) {
        this.store = Objects.requireNonNull(store);
        this.observation = Objects.requireNonNull(observation);
        this.authority = Objects.requireNonNull(authority);
        this.tokens = Objects.requireNonNull(tokens);
        this.userIds = Objects.requireNonNull(userIds);
    }

    @Override
    public Optional<Prepared> prepare(UUID requestId) {
        var stored =
                store.load(requestId)
                        .orElseThrow(() -> new IllegalStateException("Identity request missing"));
        if (stored.status() == IdentityRequestStore.Status.TERMINAL) return Optional.empty();
        var request = stored.request();
        return Optional.of(
                new Prepared() {
                    private IdentityTransitionResult actual;

                    @Override
                    public void finalizeIn(SlotCompletion completion) {
                        if (actual != null)
                            throw new IllegalStateException("Identity proposal already finalized");
                        var durable = store.load(requestId).orElseThrow();
                        if (!durable.equals(stored))
                            throw new IllegalStateException("Identity intention changed");
                        var ids =
                                authority.lockInCanonicalOrder(
                                        List.of(request.requesterEui(), request.target()));
                        var initiator = authority.current(request.requesterEui());
                        var target =
                                request.requesterEui().equals(request.target())
                                        ? initiator
                                        : authority.current(request.target());
                        actual =
                                switch (request.operation()) {
                                    case IdentityRequest.Registration ignored ->
                                            IdentityTransitions.registration(
                                                    request.requesterEui(),
                                                    target,
                                                    ids.get(request.target()),
                                                    new PocomaUserId(userIds.get()),
                                                    tokens.get());
                                    case IdentityRequest.Link ignored ->
                                            IdentityTransitions.link(
                                                    request.requesterEui(),
                                                    request.bindingToken(),
                                                    initiator,
                                                    request.target(),
                                                    target,
                                                    ids.get(request.target()),
                                                    tokens.get());
                                    case IdentityRequest.Unlink ignored ->
                                            IdentityTransitions.unlink(
                                                    request.requesterEui(),
                                                    request.bindingToken(),
                                                    initiator,
                                                    request.target(),
                                                    target);
                                };
                        if (actual instanceof IdentityTransitionResult.Changed changed)
                            authority.apply(changed, requestId);
                        store.finishTerminal(request, IdentityRequestStore.Result.from(actual));
                        completion.complete();
                        store.publish(request, actual);
                    }

                    @Override
                    public void requireCompletionReady() {
                        if (actual == null)
                            throw new IllegalStateException("Identity transition not finalized");
                        store.requireCompletionReady(request, actual);
                    }
                });
    }

    @Override
    public RecoveryObservation observe(UUID id, RecoveryReadContext context) {
        return observation.observe(id, context);
    }
}
