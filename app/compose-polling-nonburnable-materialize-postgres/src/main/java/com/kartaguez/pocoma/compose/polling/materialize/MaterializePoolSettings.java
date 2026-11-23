package com.kartaguez.pocoma.compose.polling.materialize;

import com.kartaguez.pocoma.infra.postgres.core.bootstrap.WorkerPostgresSupport;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.WorkerPostgresSupport.DevTarget;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.WorkerPostgresSupport.PoolSpec;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;

/** Technical PostgreSQL pool, endpoint, and physical dev-target configuration for Materialize. */
public record MaterializePoolSettings(
        PoolSpec sourceRead,
        PoolSpec readBusiness,
        PoolSpec readControl,
        List<DevTarget> allowedTargets) {

    public MaterializePoolSettings {
        allowedTargets = List.copyOf(allowedTargets);
    }

    static MaterializePoolSettings from(Environment env, int workerLimit) {
        var allowed = WorkerPostgresSupport.parseDevTargets(env);
        var wb = WorkerPostgresSupport.parsePool(env, "write-source-read", true, workerLimit);
        var rb = WorkerPostgresSupport.parsePool(env, "read-business", true, workerLimit);
        var rc = WorkerPostgresSupport.parsePool(env, "read-control", false, workerLimit);
        if (!WorkerPostgresSupport.sameAuthority(rb, rc)
                || (wb.database().equals(rb.database())
                        && WorkerPostgresSupport.endpoint(wb)
                                .getAuthority()
                                .equals(WorkerPostgresSupport.endpoint(rb).getAuthority()))) {
            throw new IllegalArgumentException(
                    "READ Business/Control targets must match and source WRITE must differ");
        }
        return new MaterializePoolSettings(wb, rb, rc, allowed);
    }

    public void validateTarget(PoolSpec pool, DataSource source) {
        WorkerPostgresSupport.validateTarget(allowedTargets, pool, source);
    }
}
