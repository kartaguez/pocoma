package com.kartaguez.pocoma.engine.materialize.pot;

import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.*;

public interface PotVersionSource {
    PotVersion composition(UUID eventId, RecoveryReadContext context);
    List<SubobjectVersion> contents(PotVersion composition, Collection<SubobjectKey> missing, RecoveryReadContext context);
}
