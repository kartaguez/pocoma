package com.kartaguez.pocoma.engine.consume.command.pot.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.pot.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.policy.CreatePotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.version.PotGlobalVersion;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.engine.consume.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.consume.command.model.ResolvedCommandAuthorization;
import com.kartaguez.pocoma.engine.consume.command.pot.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.write.pot.port.event.EventPublisherPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.*;

class NonPersistentDispatchRelayTest {
    @Test void dispatchCaptureCanBeRelayedWithoutADurableAppendOrDeclaration() {
        UUID user = UUID.randomUUID();
        var adapter = new CreatePotCommandUseCaseAdapter(new PotGlobalVersionPort() {
            @Override public void save(PotGlobalVersion version) {}
        }, new PotHeaderPort() {
            @Override public void saveNew(PotHeader header, long version) {}
        }, new CreatePotAuthorizationPolicy());
        var result = assertInstanceOf(CommandUseCaseResult.Succeeded.class, adapter.execute(
                new ResolvedCommandAuthorization(new PocomaUserId(user), Set.of(new Permission("POT", "CREATE"))),
                new CreatePotCommand("Trip", user)));
        var received = new ArrayList<PotCreatedEvent>();
        EventPublisherPort destination = new EventPublisherPort() {
            @Override public void publish(PotCreatedEvent event) { received.add(event); }
        };
        assertTrue(received.isEmpty()); // Capture was not publication to this destination.
        for (var event : result.events()) destination.publish(assertInstanceOf(PotCreatedEvent.class, event));
        assertEquals(result.events(), received);
        assertEquals(1, received.size());
    }
}
