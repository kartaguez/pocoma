package com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event;

import java.util.List;
import com.kartaguez.pocoma.port.transaction.EventFamily;
import com.kartaguez.pocoma.port.transaction.EventRef;

/** Closed value snapshot; never contains entities, writers or application callbacks. */
public final class ClosedPublishedEvents {
    private final List<EventRef> events;

    ClosedPublishedEvents(List<EventRef> events) { this.events = List.copyOf(events); }

    public List<EventRef> events() { return events; }
    public List<EventRef> inFamily(EventFamily family) {
        return events.stream().filter(event -> event.family() == family).toList();
    }
    public boolean isEmpty() { return events.isEmpty(); }
}
