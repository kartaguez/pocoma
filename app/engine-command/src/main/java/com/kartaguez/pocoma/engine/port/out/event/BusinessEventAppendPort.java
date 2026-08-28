package com.kartaguez.pocoma.engine.port.out.event;

import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;

/** Durable append contract for typed Pot business events. */
public interface BusinessEventAppendPort {

	void append(BusinessEvent event);
}
