package com.kartaguez.pocoma.engine.port.out.persistence;

import java.util.List;
import java.util.Optional;

import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;

public interface PotQueryPort {

	default PotGlobalVersion currentVersion(PotId potId) {
		throw new UnsupportedOperationException("Pot current version loading is not implemented");
	}

	default PotHeader loadPotHeaderAtVersion(PotId potId, long version) {
		throw new UnsupportedOperationException("Pot header query loading is not implemented");
	}

	default PotShareholders loadPotShareholdersAtVersion(PotId potId, long version) {
		throw new UnsupportedOperationException("Pot shareholders query loading is not implemented");
	}

	default List<VersionedPotHeader> listAccessiblePotHeaders(UserId userId) {
		throw new UnsupportedOperationException("Accessible pot headers listing is not implemented");
	}

	default Optional<Shareholder> findLinkedShareholderAtVersion(UserId userId, PotId potId, long version) {
		throw new UnsupportedOperationException("Linked shareholder lookup is not implemented");
	}

	record VersionedPotHeader(PotHeader potHeader, long version) {
	}
}
