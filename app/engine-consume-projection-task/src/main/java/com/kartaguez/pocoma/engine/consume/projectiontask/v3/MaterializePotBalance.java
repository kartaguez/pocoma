package com.kartaguez.pocoma.engine.consume.projectiontask.v3;
import com.kartaguez.pocoma.domain.projection.v3.PotBalance;
import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.port.projection.v3.PotBalanceStore;
import java.util.*;
public final class MaterializePotBalance implements ConsumptionParticipant {
    private final PotBalanceSource source; private final PotBalanceStore store;
    private final PotBalanceCalculator calculator=new PotBalanceCalculator();
    public MaterializePotBalance(PotBalanceSource source,PotBalanceStore store) { this.source=Objects.requireNonNull(source); this.store=Objects.requireNonNull(store); }
    private Optional<PotBalance> calculate(PotFinancialState state) {
        return calculator.calculate(state).map(b -> { var values=new HashMap<UUID,Fraction>(); b.values().forEach((k,v) -> values.put(k.value(),v)); return new PotBalance(b.potId().value(),b.version(),values); });
    }
    public Optional<Prepared> prepare(UUID id,PreparationReads reads) {
        var state=reads.source(c -> source.load(id,c)); var balance=calculate(state);
        return Optional.of(new Prepared() {
            public void finalizeIn(SlotCompletion completion) { balance.ifPresent(store::insertOrVerify); completion.complete(); store.closePublication(); }
            public void requireCompletionReady() { store.requireStored(state.potId().value(),state.version(),balance); }
        });
    }
    public Recovery prepareRecovery(UUID id,PreparationReads reads) {
        var state=reads.source(c -> source.load(id,c)); var expected=calculate(state);
        return c -> {
            return EffectEvidence.valueOf(store.evidence(state.potId().value(),state.version(),expected,c).name());
        };
    }
}
