package com.kartaguez.pocoma.engine.write.pot.port.event;

import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;

/** Durable append contract for typed Pot business events. */
public interface BusinessEventAppendPort {

	void append(BusinessEvent event);
}
