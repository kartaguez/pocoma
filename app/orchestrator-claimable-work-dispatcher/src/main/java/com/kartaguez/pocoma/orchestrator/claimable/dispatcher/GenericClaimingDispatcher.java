package com.kartaguez.pocoma.orchestrator.claimable.dispatcher;

import java.util.Set;
import java.util.function.Predicate;

import com.kartaguez.pocoma.orchestrator.claimable.pool.SegmentedWorkHandler;
import com.kartaguez.pocoma.orchestrator.claimable.wake.WorkWakeBus;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimableWorkSource;
import com.kartaguez.pocoma.orchestrator.claimable.work.ClaimedWork;
import com.kartaguez.pocoma.orchestrator.claimable.work.WorkKeyResolver;

public class GenericClaimingDispatcher<W, K, S, C> extends ClaimableWorkDispatcher<W, K, S, C> {

	public GenericClaimingDispatcher(
			ClaimableWorkSource<W, C> workSource,
			SegmentedWorkHandler<ClaimedWork<W>, K> workHandler,
			WorkKeyResolver<W, K> keyResolver,
			C claimCriteria,
			ClaimableWorkDispatcherSettings settings,
			WorkWakeBus<S, K> wakeBus,
			Set<S> wakeSignals,
			Predicate<K> wakeKeyPredicate) {
		super(
				workSource,
				workHandler,
				keyResolver,
				claimCriteria,
				settings,
				wakeBus,
				wakeSignals,
				wakeKeyPredicate);
	}
}
