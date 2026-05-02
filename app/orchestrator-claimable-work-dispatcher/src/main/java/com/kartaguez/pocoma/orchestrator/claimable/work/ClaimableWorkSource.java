package com.kartaguez.pocoma.orchestrator.claimable.work;

import java.util.List;

public interface ClaimableWorkSource<T, C> {

	List<ClaimedWork<T>> claim(ClaimWorkRequest<C> request);

	boolean markAccepted(ClaimedWork<T> work);

	void release(ClaimedWork<T> work);

	boolean markProcessing(ClaimedWork<T> work);

	boolean markDone(ClaimedWork<T> work);

	boolean markFailed(ClaimedWork<T> work, RuntimeException error);
}
