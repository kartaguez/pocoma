package com.kartaguez.pocoma.pipeline.balance.projection;

public interface BalanceProjectionPort {
	BalanceProjectionPersistenceResult createOrVerify(BalanceProjectionArtifact artifact);
}
