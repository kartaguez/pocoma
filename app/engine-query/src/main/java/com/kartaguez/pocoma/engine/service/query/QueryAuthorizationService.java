package com.kartaguez.pocoma.engine.service.query;

import java.util.Objects;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.policy.ReadPotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.out.persistence.PotQueryPort;
import com.kartaguez.pocoma.engine.security.UserContext;

final class QueryAuthorizationService {

	private final PotQueryPort potQueryPort;
	private final ReadPotAuthorizationPolicy readPotAuthorizationPolicy;

	QueryAuthorizationService(PotQueryPort potQueryPort, ReadPotAuthorizationPolicy readPotAuthorizationPolicy) {
		this.potQueryPort = Objects.requireNonNull(potQueryPort, "potQueryPort must not be null");
		this.readPotAuthorizationPolicy = Objects.requireNonNull(
				readPotAuthorizationPolicy,
				"readPotAuthorizationPolicy must not be null");
	}

	void assertCanRead(UserContext userContext, PotHeader potHeader, PotId potId, long version) {
		Objects.requireNonNull(userContext, "userContext must not be null");
		Objects.requireNonNull(potHeader, "potHeader must not be null");
		Objects.requireNonNull(potId, "potId must not be null");
		boolean linkedShareholder = false;
		if (userContext.userId() != null) {
			linkedShareholder = potQueryPort
					.findLinkedShareholderAtVersion(UserId.of(java.util.UUID.fromString(userContext.userId())), potId, version)
					.isPresent();
		}
		readPotAuthorizationPolicy.assertCanReadPot(userContext.userId(), potHeader.creatorId(), linkedShareholder);
	}
}
