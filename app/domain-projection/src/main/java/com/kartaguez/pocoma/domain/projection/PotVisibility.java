package com.kartaguez.pocoma.domain.projection;
import java.util.*;
public record PotVisibility(UUID potId,long version,Set<UUID> users) {
    public PotVisibility { Objects.requireNonNull(potId); if(version<1) throw new IllegalArgumentException("Positive visibility version required"); users=Set.copyOf(users); }
}
