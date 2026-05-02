package com.kartaguez.pocoma.engine.port.in.work;

import java.util.List;

public interface ClaimWorkUseCase<W, C> {

	List<W> claimWork(ClaimWorkCommand<C> command);

	boolean markWorkAccepted(W work);

	void releaseWork(W work);
}
