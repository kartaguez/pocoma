package com.kartaguez.pocoma.engine.admit.command.v3;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionAssociation;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionFailure;
import com.kartaguez.pocoma.contract.writeadmission.AdmissionProtection;
import com.kartaguez.pocoma.contract.writeadmission.CommandAdmissionInput;

import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.contract.writeadmission.*;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;

import java.time.*;
import java.util.*;

/** S-03 admission only. No business authority port is available here. */
public final class AdmitCommandV3 implements com.kartaguez.pocoma.contract.writeadmission.AdmitCommand {
    public interface Store extends AdmissionAssociation.Store {
        void insert(PotCommand request);
    }

    private final Store store;
    private final AttemptTransactions transactions;
    private final AdmissionProtection protection;
    private final Clock clock;
    private final int textBytes;

    public AdmitCommandV3(
            Store store,
            AttemptTransactions transactions,
            AdmissionProtection protection,
            Clock clock,
            int textBytes) {
        this.store = Objects.requireNonNull(store);
        this.transactions = Objects.requireNonNull(transactions);
        this.protection = Objects.requireNonNull(protection);
        this.clock = Objects.requireNonNull(clock);
        if (textBytes < 1) throw new IllegalArgumentException("Positive text bound required");
        this.textBytes = textBytes;
    }

    public UUID admit(
            AuthenticatedExternalPrincipal principal,
            String clientKey,
            CommandAdmissionInput input) {
        if (principal == null || !clock.instant().isBefore(principal.expiresAt()))
            throw new AdmissionFailure(401);
        var requester = input.command(new UUID(0, 0)).requester();
        AdmissionFailure.require(
                principal.issuer().equals(requester.issuer())
                        && principal.subject().equals(requester.subject()));
        byte[] scope =
                protection.scope(
                        principal.issuer(),
                        principal.subject(),
                        Optional.empty(),
                        "COMMAND",
                        input.operation(),
                        clientKey);
        try {
            var found = transactions.read(context -> store.find(scope));
            if (found.isPresent()) return replay(found.get(), input);
            input.validateCurrent(textBytes);
            byte[] digest = protection.intent(protection.currentId(), 1, input.canonical(1));
            var association =
                    new AdmissionAssociation(
                            UUID.randomUUID(),
                            "COMMAND",
                            input.operation(),
                            1,
                            protection.lookupId(),
                            protection.currentId(),
                            scope,
                            digest,
                            clock.instant());
            return transactions.write(
                    () -> {
                        if (!store.reserve(association))
                            return replay(
                                    store.find(scope).orElseThrow(() -> new AdmissionFailure(503)),
                                    input);
                        store.insert(input.command(association.requestId()));
                        return association.requestId();
                    });
        } catch (AdmissionFailure failure) {
            throw failure;
        } catch (AttemptTransactions.Failure failure) {
            // A conflict thrown inside the WRITE callback is wrapped by its physical owner.
            if (failure.getCause() instanceof AdmissionFailure safe) throw safe;
            throw new AdmissionFailure(503);
        } catch (RuntimeException failure) {
            throw new AdmissionFailure(503);
        }
    }

    private UUID replay(AdmissionAssociation association, CommandAdmissionInput input) {
        if (!protection.matches(association, input.canonical(association.canonicalVersion())))
            throw new AdmissionFailure(409);
        return association.requestId();
    }
}
