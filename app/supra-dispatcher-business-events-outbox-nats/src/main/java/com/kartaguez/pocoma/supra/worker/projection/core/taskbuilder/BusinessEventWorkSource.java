package com.kartaguez.pocoma.supra.worker.projection.core.taskbuilder;

import java.util.List;
import java.util.Objects;

import com.kartaguez.pocoma.engine.model.BusinessEventClaim;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.port.out.persistence.BusinessEventOutboxPort;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimWorkRequest;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkSource;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;

public final class BusinessEventWorkSource implements ClaimableWorkSource<BusinessEventEnvelope, ProjectionPartition> {

	private final BusinessEventOutboxPort outboxPort;

	public BusinessEventWorkSource(BusinessEventOutboxPort outboxPort) {
		this.outboxPort = Objects.requireNonNull(outboxPort, "outboxPort must not be null");
	}

	@Override
	public List<ClaimedWork<BusinessEventEnvelope>> claim(ClaimWorkRequest<ProjectionPartition> request) {
		Objects.requireNonNull(request, "request must not be null");
		return outboxPort.claimPending(
				request.limit(),
				request.leaseDuration(),
				request.workerId(),
				request.criteria())
				.stream()
				.map(claim -> new ClaimedBusinessEventWork(claim.event(), claim.claimToken()))
				.map(work -> (ClaimedWork<BusinessEventEnvelope>) work)
				.toList();
	}

	@Override
	public boolean markAccepted(ClaimedWork<BusinessEventEnvelope> work) {
		return outboxPort.markAccepted(work.instruction().id(), claimToken(work));
	}

	@Override
	public void release(ClaimedWork<BusinessEventEnvelope> work) {
		outboxPort.release(work.instruction().id(), claimToken(work));
	}

	@Override
	public boolean markProcessing(ClaimedWork<BusinessEventEnvelope> work) {
		return outboxPort.markRunning(work.instruction().id(), claimToken(work));
	}

	@Override
	public boolean markDone(ClaimedWork<BusinessEventEnvelope> work) {
		return outboxPort.markDone(work.instruction().id(), claimToken(work));
	}

	@Override
	public boolean markFailed(ClaimedWork<BusinessEventEnvelope> work, RuntimeException error) {
		return outboxPort.markFailed(work.instruction().id(), claimToken(work), error.getMessage());
	}

	private static java.util.UUID claimToken(ClaimedWork<BusinessEventEnvelope> work) {
		if (work instanceof ClaimedBusinessEventWork typed) {
			return typed.claimToken();
		}
		throw new IllegalArgumentException("Business event work must carry a claim token");
	}

	private static final class ClaimedBusinessEventWork extends ClaimedWork<BusinessEventEnvelope> {
		private final java.util.UUID claimToken;

		private ClaimedBusinessEventWork(BusinessEventEnvelope instruction, java.util.UUID claimToken) {
			super(instruction);
			this.claimToken = Objects.requireNonNull(claimToken, "claimToken must not be null");
		}

		private java.util.UUID claimToken() {
			return claimToken;
		}
	}
}
