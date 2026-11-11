package com.kartaguez.pocoma.orchestrator.consumption.v3;

import static org.junit.jupiter.api.Assertions.*;
import com.kartaguez.pocoma.contract.consumption.invocation.*;
import com.kartaguez.pocoma.contract.consumption.participation.*;
import com.kartaguez.pocoma.contract.consumption.spi.*;
import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition.Authority;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.Resolution;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import org.junit.jupiter.api.Test;

/** API boundary adversaries; physical SQL invariants retain their PostgreSQL oracles. */
class ConsumptionInvocationContractTest {
    static final ConsumptionDefinition DEFINITION = new ConsumptionDefinition(
            new ConsumptionId(new UUID(0, 77)), ConsumableFamily.COMMAND, Authority.WRITE);
    static final UUID ITEM = new UUID(0, 83);
    @SuppressWarnings("unchecked")
    static <T> T port(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }
    static final class Fixture {
        final AtomicInteger transactions = new AtomicInteger();
        final WorkerCapacity capacity = new WorkerCapacity(2, Map.of(DEFINITION.id(), 2));
        final AttemptTransactions owner = new AttemptTransactions() {
            public <T> T read(Function<RecoveryReadContext,T> action, Guard guard) {
                transactions.incrementAndGet(); guard.requireValid(); return action.apply(guard::requireValid);
            }
            public <T> T write(Supplier<T> action, Guard guard) {
                transactions.incrementAndGet(); guard.requireValid(); return action.get();
            }
            public void poison(Throwable failure) { throw new AssertionError("Unexpected effect"); }
            public void requirePublicationClosed() {}
        };
        final PartitionOwnershipStore ownership = port(PartitionOwnershipStore.class, (p,m,args) -> switch(m.getName()) {
            case "bound" -> null;
            case "acquire" -> Optional.of(new PartitionLease(DEFINITION.id(), (int)args[1],
                    (UUID)args[2], Instant.now().plusSeconds(120)));
            case "protectAcquisition" -> Optional.of((PartitionLease)args[1]);
            case "renew" -> args[1];
            default -> throw new AssertionError(m.getName());
        });
        final ConsumptionSlotStore slots = port(ConsumptionSlotStore.class, (p,m,args) -> {
            if (m.getName().equals("acquire")) {
                @SuppressWarnings("unchecked") var candidates = (List<ConsumptionSlotStore.ClaimCandidate>)args[1];
                return candidates.stream().map(c -> new ConsumptionSlotStore.GrantedClaim(
                        new ClaimedSlot(DEFINITION.id(), c.consumableId(), c.freshToken()),
                        Instant.now().plusSeconds(120))).toList();
            }
            throw new AssertionError(m.getName());
        });
        final ConsumptionParticipant participant = new ConsumptionParticipant() {
            public Optional<Prepared> prepare(UUID id, PreparationReads reads) { throw new AssertionError("Unexpected prepare"); }
            public Recovery prepareRecovery(UUID id, PreparationReads reads) { throw new AssertionError("Unexpected recovery"); }
        };
        final ConsumptionEngine engine = new ConsumptionEngine(DEFINITION, owner, slots, participant,
                ownership, LifecycleSettings.pilot(), capacity, failure -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
        final DiscoveryEngine discovery = new DiscoveryEngine(DEFINITION, owner, ownership,
                port(DiscoveryAdmissionStore.class, (p,m,a) -> { throw new AssertionError(m.getName()); }),
                new DiscoverySettings(32,32,32,100,Duration.ofMillis(10),Duration.ofMillis(20)),
                new BurnableReferences() {
                    public ConsumableFamily family() { return ConsumableFamily.COMMAND; }
                    public Window window(long after,int max,RecoveryReadContext context) { return new Window(after,after,List.of()); }
                    public boolean tryProtectPending(UUID id) { return false; }
                    public boolean isTerminal(UUID id) { return false; }
                });
        final ConsumptionProtocol protocol = new BurnableConsumptionProtocol(DEFINITION, engine, discovery);
        ConsumptionProtocol.Partition partition() {
            return protocol.acquirePartition(RoutingV1.partition(RoutingV1.hash(ConsumableFamily.COMMAND, ITEM),4)).orElseThrow();
        }
        ConsumptionProtocol.Handle claim() { return protocol.acquire(partition(),List.of(ITEM)).claims().getFirst(); }
    }
    @Test void fabricatedAndForeignHandlesFailBeforeTransactions() {
        var a = new Fixture(); var b = new Fixture(); var issued = a.claim();
        var fake = new ConsumptionProtocol.Handle() {
            public ConsumptionId consumptionId() { return issued.consumptionId(); }
            public UUID consumableId() { return issued.consumableId(); }
            public int partitionId() { return issued.partitionId(); }
        };
        int beforeA = a.transactions.get(), beforeB = b.transactions.get();
        assertThrows(IllegalArgumentException.class, () -> a.protocol.execute(fake));
        assertThrows(IllegalArgumentException.class, () -> b.protocol.execute(issued));
        assertThrows(IllegalArgumentException.class, () -> b.protocol.abandon(issued));
        assertThrows(IllegalArgumentException.class, () -> b.protocol.retainsCapacity(issued));
        assertEquals(beforeA,a.transactions.get()); assertEquals(beforeB,b.transactions.get());
        assertEquals(1,a.capacity.snapshot().worker());
        a.protocol.abandon(issued);
    }
    @Test void foreignPartitionsAndDoubleUseAreRefused() {
        var a = new Fixture(); var b = new Fixture(); var partition = a.partition();
        int before = b.transactions.get();
        assertThrows(IllegalArgumentException.class, () -> b.protocol.discover(partition));
        assertThrows(IllegalArgumentException.class, () -> b.protocol.acquire(partition,List.of(ITEM)));
        assertEquals(before,b.transactions.get());
        var handle = a.claim(); a.protocol.abandon(handle); before = a.transactions.get();
        assertThrows(IllegalArgumentException.class, () -> a.protocol.execute(handle));
        assertThrows(IllegalArgumentException.class, () -> a.protocol.abandon(handle));
        assertEquals(before,a.transactions.get()); assertEquals(0,a.capacity.snapshot().worker());
    }
    @Test void renewalPermitsAreScopedSingleUseAndUnknownRetainsLanes() {
        var a = new Fixture(); var b = new Fixture(); var handle = a.claim();
        var scope = new ConsumptionSafety(List.of(a.protocol),a.capacity);
        var foreign = new ConsumptionSafety(List.of(b.protocol),b.capacity);
        var permit = scope.beginRenewal(Authority.WRITE,0,handle).orElseThrow();
        assertTrue(scope.beginRenewal(Authority.WRITE,1,handle).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> foreign.finishRenewal(permit,null));
        assertThrows(IllegalArgumentException.class, () -> scope.finishRenewal(new ProtocolSafety.RenewalPermit(){},null));
        var outcome = scope.finishRenewal(permit,new AttemptTransactions.Failure(Resolution.UNKNOWN,new IllegalStateException("lost ACK")));
        assertFalse(outcome.physicallyResolved()); assertTrue(outcome.requestDrain());
        assertFalse(scope.renewalsQuiescent());
        assertTrue(scope.beginRenewal(Authority.WRITE,0,handle).isEmpty());
        assertTrue(scope.beginRenewal(Authority.WRITE,1,handle).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> scope.finishRenewal(permit,null));
        a.protocol.abandon(handle);
    }
    @Test void resolvedRenewalReleasesOnlyItsPermitAndCapacityScopeCannotBeReplaced() {
        var f = new Fixture(); var scope = new ConsumptionSafety(List.of(f.protocol),f.capacity);
        assertThrows(IllegalArgumentException.class, () -> new ConsumptionSafety(List.of(f.protocol),f.capacity));
        assertThrows(IllegalArgumentException.class, () -> new ConsumptionSafety(List.of(f.protocol),new WorkerCapacity(2,Map.of(DEFINITION.id(),2))));
        var first = scope.beginRenewal(Authority.WRITE,2,null).orElseThrow();
        assertTrue(scope.finishRenewal(first,new AttemptTransactions.Failure(Resolution.ROLLED_BACK,new IllegalStateException("confirmed"))).physicallyResolved());
        assertTrue(scope.renewalsQuiescent());
        var second = scope.beginRenewal(Authority.WRITE,2,null).orElseThrow();
        assertNotSame(first,second);
        assertThrows(IllegalArgumentException.class, () -> scope.finishRenewal(first,null));
        assertFalse(scope.renewalsQuiescent());
        scope.finishRenewal(second,null); assertTrue(scope.renewalsQuiescent());
    }
    @Test void variantAndDefinitionWiringAreValidatedBeforeActivation() {
        var f = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> new NonBurnableConsumptionProtocol(DEFINITION,f.engine,f.discovery));
        var other = new ConsumptionDefinition(new ConsumptionId(new UUID(0,99)),ConsumableFamily.COMMAND,Authority.WRITE);
        assertThrows(IllegalArgumentException.class, () -> new BurnableConsumptionProtocol(other,f.engine,f.discovery));
        assertEquals(0,f.transactions.get());
    }
}
