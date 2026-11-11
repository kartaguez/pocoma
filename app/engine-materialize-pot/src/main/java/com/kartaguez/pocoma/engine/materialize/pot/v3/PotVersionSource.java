package com.kartaguez.pocoma.engine.materialize.pot.v3;

import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import java.util.*;

public interface PotVersionSource {
    PotVersion composition(UUID eventId, RecoveryReadContext context);
    List<SubobjectVersion> contents(PotVersion composition, Collection<SubobjectKey> missing, RecoveryReadContext context);
}
