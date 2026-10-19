package com.kartaguez.pocoma.engine.consume.command.model;

import java.util.HashSet;
import java.util.List;

import com.kartaguez.pocoma.port.transaction.EventFamily;
import com.kartaguez.pocoma.port.transaction.EventRef;

/**
 * TARGET appendAll acceptance: one unchanged provenance artifact and stable reference
 * per accepted Event, in input order. Empty input has two empty lists. This is a
 * provisional atomic publication commitment, not evidence of commit or visibility.
 * CURRENT EventAppendPort will adopt this result together with its callers in 4D.
 */
public record BusinessEventsAccepted(List<CommandExecutionArtifact> artifacts, List<EventRef> events) {
    public BusinessEventsAccepted {
        artifacts = List.copyOf(artifacts);
        events = List.copyOf(events);
        if (artifacts.size() != events.size() || new HashSet<>(events).size() != events.size()) {
            throw new IllegalArgumentException("One distinct reference per artifact is required");
        }
        for (int i = 0; i < events.size(); i++) {
            var ref = events.get(i);
            var artifact = artifacts.get(i);
            if (ref.family() != EventFamily.BUSINESS_EVENT || !artifact.space().equals("EVENT")
                    || !artifact.id().equals(ref.eventId().toString())) {
                throw new IllegalArgumentException("Artifact and accepted business Event identity differ");
            }
        }
    }
}
