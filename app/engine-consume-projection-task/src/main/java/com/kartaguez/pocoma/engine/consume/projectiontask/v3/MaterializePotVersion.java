package com.kartaguez.pocoma.engine.consume.projectiontask.v3;

import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.port.projection.v3.*;
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
        var present=reads.effects(c -> subobjects.readAll(value.keys(),c));
        var missing=value.keys().stream().filter(k -> !present.containsKey(k)).toList();
        var loaded=missing.isEmpty() ? List.<SubobjectVersion>of() : reads.source(c -> source.contents(value,missing,c));
        var expected=new TreeMap<SubobjectKey,SubobjectVersion>(present);
        for (var s:loaded) if (expected.putIfAbsent(s.key(),s)!=null) throw new IllegalStateException("Duplicate loaded key");
        if (!expected.keySet().equals(new TreeSet<>(value.keys()))) throw new IllegalStateException("Incomplete source contents");
        var all=List.copyOf(expected.values());
        return Optional.of(new Prepared() {
            public void finalizeIn(SlotCompletion completion) {
                subobjects.insertOrVerifyAll(loaded); versions.insertAndAdvance(value); completion.complete(); versions.closePublication();
            }
            public void requireCompletionReady() { subobjects.requireStored(all); versions.requireStored(value); }
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
