package com.kartaguez.pocoma.engine.event;

import com.kartaguez.pocoma.domain.value.id.PotId;

/** Marker for immutable, typed business facts produced inside the application. */
public interface BusinessEvent {

	PotId potId();

	long version();
}
