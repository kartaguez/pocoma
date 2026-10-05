package com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.outbox;

enum BusinessEventOutboxStatus {
	PENDING,
	CLAIMED,
	ACCEPTED,
	RUNNING,
	PROCESSED,
	FAILED
}
