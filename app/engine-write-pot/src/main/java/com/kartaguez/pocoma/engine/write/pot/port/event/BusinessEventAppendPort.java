package com.kartaguez.pocoma.engine.write.pot.port.event;

import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;

/** Durable append participant. Returns the exact accepted Event UUID; commit belongs to its caller. */
public interface BusinessEventAppendPort {

	java.util.UUID append(BusinessEvent event);
}
