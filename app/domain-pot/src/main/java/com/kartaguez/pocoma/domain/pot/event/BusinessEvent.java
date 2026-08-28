package com.kartaguez.pocoma.domain.pot.event;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

/** Marker for immutable, typed business facts produced inside the application. */
public interface BusinessEvent {

	PotId potId();

	long version();
}
