package com.kartaguez.pocoma.engine.service.consumption;

import java.sql.SQLException;
import java.util.UUID;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;
import com.kartaguez.pocoma.engine.exception.consumption.ConsumptionClaimClosureException;
import com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionQueryPort;

/** Used only outside the physical owner, after rollback; never hides a current owner's SQL error. */
public final class ConsumptionOwnership {
	private static final System.Logger LOG = System.getLogger(ConsumptionOwnership.class.getName());
	private ConsumptionOwnership() {}
	public static boolean lostAfterSqlRollback(ConsumptionQueryPort query, UUID slotId, ClaimId claimId, RuntimeException failure) {
		if (query == null || failure instanceof ConsumptionClaimClosureException
				|| failure instanceof ConsumptionTransactionResolutionException) return false;
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql && sql.getSQLState() != null
					&& (sql.getSQLState().startsWith("23") || sql.getSQLState().startsWith("40"))) {
				boolean lost;
				try { lost = noLongerCurrent(query, slotId, claimId); }
				catch (RuntimeException lookupFailure) {
					lookupFailure.addSuppressed(failure);
					throw lookupFailure;
				}
				if (lost) LOG.log(System.Logger.Level.DEBUG, "Abandoning stale Consumption after rollback, SQLSTATE=" + sql.getSQLState(), failure);
				return lost;
			}
		}
		return false;
	}
	public static boolean noLongerCurrent(ConsumptionQueryPort query, UUID slotId, ClaimId claimId) {
		var slot = query.findSlot(slotId).orElseThrow(() -> new IllegalStateException("Ownership cannot be established: missing slot " + slotId));
		return slot.status() == ConsumptionStatus.DONE || slot.currentClaimId().filter(claimId::equals).isEmpty();
	}
}
