package com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox;

enum BusinessEventOutboxStatus {
	PENDING,
	CLAIMED,
	ACCEPTED,
	RUNNING,
	PROCESSED,
	FAILED
}
