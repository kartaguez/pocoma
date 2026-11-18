package com.kartaguez.pocoma.engine.materialize.pot;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;

import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.port.projection.*;
import java.util.*;

/** Inert participant; only L2 owns snapshots, final transaction and commit resolution. */
public final class MaterializePotVersion implements ConsumptionParticipant {
    private final PotVersionSource source;
    private final SubobjectVersionStore subobjects;
    private final PotVersionStore versions;
    public MaterializePotVersion(PotVersionSource source, SubobjectVersionStore subobjects, PotVersionStore versions) {
        this.source=Objects.requireNonNull(source); this.subobjects=Objects.requireNonNull(subobjects); this.versions=Objects.requireNonNull(versions);
    }
    public Optional<Prepared> prepare(UUID event, PreparationReads reads) {
        var value=reads.source(c -> source.composition(event,c));
        var presentKeys=reads.effects(c -> subobjects.existingKeys(value.keys(),c));
        var missing=value.keys().stream().filter(k -> !presentKeys.contains(k)).toList();
        var loaded=missing.isEmpty() ? List.<SubobjectVersion>of() : reads.source(c -> source.contents(value,missing,c));
        var expectedKeys=new TreeSet<SubobjectKey>(presentKeys);
        for (var s:loaded) if (!expectedKeys.add(s.key())) throw new IllegalStateException("Duplicate loaded key");
        if (!expectedKeys.equals(new TreeSet<>(value.keys()))) throw new IllegalStateException("Incomplete source contents");
        return Optional.of(new Prepared() {
            public void finalizeIn(SlotCompletion completion) {
                subobjects.insertOrVerifyAll(loaded); versions.insertAndAdvance(value); completion.complete(); versions.closePublication();
            }
            public void requireCompletionReady() {
                subobjects.requireStored(loaded);
                subobjects.requireKeysPresent(presentKeys);
                versions.requireStored(value);
            }
        });
    }
    public Recovery prepareRecovery(UUID event, PreparationReads reads) {
        var expected=reads.source(c -> source.composition(event,c));
        var contents=reads.source(c -> source.contents(expected,expected.keys(),c));
        return c -> {
            var root=versions.evidence(expected,c);
            if(root!=ReadEvidence.SATISFIED) return EffectEvidence.valueOf(root.name());
            if(contents.size()!=expected.keys().size()) return EffectEvidence.DIVERGENT;
            return EffectEvidence.valueOf(subobjects.evidence(contents,c).name());
        };
    }
}
