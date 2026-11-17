package com.kartaguez.pocoma.engine.materialize.pot;
import com.kartaguez.pocoma.domain.projection.PotVisibility;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.UUID;
public interface PotVisibilitySource {
    PotVisibility load(UUID event,RecoveryReadContext context);
    PotVisibility at(UUID pot,long version,RecoveryReadContext context);
}
