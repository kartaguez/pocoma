package com.kartaguez.pocoma.engine.port.out.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.ProjectionStatus;

class TerminalProjectionStateTest {

	@Test
	void acceptsReadyAndFailedWithValueSemantics() {
		assertEquals(
				new TerminalProjectionState(15, ProjectionStatus.READY),
				new TerminalProjectionState(15, ProjectionStatus.READY));
		assertEquals(ProjectionStatus.FAILED,
				new TerminalProjectionState(14, ProjectionStatus.FAILED).status());
	}

	@Test
	void rejectsInvalidVersionNullAndNonTerminalStatus() {
		assertThrows(IllegalArgumentException.class,
				() -> new TerminalProjectionState(0, ProjectionStatus.READY));
		assertThrows(IllegalArgumentException.class,
				() -> new TerminalProjectionState(-1, ProjectionStatus.FAILED));
		assertThrows(NullPointerException.class,
				() -> new TerminalProjectionState(1, null));
		assertThrows(IllegalArgumentException.class,
				() -> new TerminalProjectionState(1, ProjectionStatus.NOT_READY));
	}
}
