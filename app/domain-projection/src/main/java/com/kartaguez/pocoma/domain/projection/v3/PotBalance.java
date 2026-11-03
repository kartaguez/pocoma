package com.kartaguez.pocoma.domain.projection.v3;
import com.kartaguez.pocoma.domain.pot.v3.Fraction;
import java.util.*;
public record PotBalance(UUID potId,long version,Map<UUID,Fraction> values) {
    public PotBalance {
        Objects.requireNonNull(potId); if(version<1) throw new IllegalArgumentException("Positive version required");
        var sorted=new TreeMap<UUID,Fraction>(SubobjectKey::compareUuid); sorted.putAll(values);
        sorted.forEach((id,f) -> { Objects.requireNonNull(id); Objects.requireNonNull(f); });
        if(!sorted.values().stream().reduce(Fraction.ZERO,Fraction::add).equals(Fraction.ZERO)) throw new IllegalArgumentException("Unbalanced vector");
        values=Collections.unmodifiableMap(sorted);
    }
}
