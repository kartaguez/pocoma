package com.kartaguez.pocoma.engine.port.out.persistence;

public interface BusinessEventOutboxAppendPort {

	void append(Object event);
}
