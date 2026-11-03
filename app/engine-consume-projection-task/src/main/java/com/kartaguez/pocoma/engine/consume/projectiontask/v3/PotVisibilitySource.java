package com.kartaguez.pocoma.engine.consume.projectiontask.v3;
import com.kartaguez.pocoma.domain.projection.v3.PotVisibility;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import java.util.UUID;
public interface PotVisibilitySource {
    PotVisibility load(UUID event,RecoveryReadContext context);
    PotVisibility at(UUID pot,long version,RecoveryReadContext context);
}
