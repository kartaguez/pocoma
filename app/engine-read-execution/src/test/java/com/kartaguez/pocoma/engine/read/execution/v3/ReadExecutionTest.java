package com.kartaguez.pocoma.engine.read.execution.v3;
import com.kartaguez.pocoma.contract.readquery.*;
import static com.kartaguez.pocoma.contract.readquery.ReadPotV3Result.*;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.port.transaction.v3.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
class ReadExecutionTest {
    private static final ExternalIdentity EUI=new ExternalIdentity("issuer","subject");
    private static final UUID POT=new UUID(0,1);
    @Test void quotaIsSharedAcrossQueriesAndRejectedInputNeverBeginsSnapshot() throws Exception {
        var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var begins=new AtomicInteger();
        RecoveryReadContext context=() -> {};
        var provider=(SnapshotRead)Proxy.newProxyInstance(SnapshotRead.class.getClassLoader(),new Class<?>[]{SnapshotRead.class},(p,m,a) -> {
            assertSame(context,a[a.length-1]);entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return new NotFound<>();
        });
        var owner=new ReadTransactions() { public <T> T read(Function<RecoveryReadContext,T> action) {begins.incrementAndGet();return action.apply(context);} };
        var service=new ReadExecution(owner,provider,new ReadLimitsV3(50,100,16_000_000),false,ReadObservation.NONE);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(() -> service.current(EUI,"token",POT,0));
            var second=executor.submit(() -> service.at(EUI,"token",POT,1));
            try {
                assertTrue(entered.await(2,TimeUnit.SECONDS));
                assertInstanceOf(Unavailable.class,service.commandResult(POT,EUI));
                assertInstanceOf(InvalidInput.class,service.expenses(EUI,"token",POT,1,List.of()));
                assertEquals(2,begins.get());
            } finally {release.countDown();}
            assertInstanceOf(NotFound.class,first.get(2,TimeUnit.SECONDS));assertInstanceOf(NotFound.class,second.get(2,TimeUnit.SECONDS));
        }
        assertInstanceOf(NotFound.class,service.current(EUI,"token",POT,0));assertEquals(3,begins.get());
    }
    @Test void technicalFailureReleasesQuotaAndObservesFailureWithoutLeakingIt() {
        var fail=new AtomicBoolean(true);var rejection=new AtomicInteger();var outcomes=new ArrayList<String>();
        var owner=new ReadTransactions() { public <T> T read(Function<RecoveryReadContext,T> action) {if(fail.get())throw new IllegalStateException("private diagnostic");return action.apply(() -> {});} };
        var queries=(SnapshotRead)Proxy.newProxyInstance(SnapshotRead.class.getClassLoader(),new Class<?>[]{SnapshotRead.class},(p,m,a) -> new NotFound<>());
        var available=new AtomicReference<java.util.function.IntSupplier>();
        var observer=new ReadObservation() {
            public void quota(java.util.function.IntSupplier permits) {available.set(permits);}
            public void rejected() {rejection.incrementAndGet();}
            public void record(String operation,String outcome,long elapsed) {outcomes.add(outcome);}
        };
        var service=new ReadExecution(owner,queries,new ReadLimitsV3(50,100,16_000_000),true,observer);
        assertInstanceOf(Unavailable.class,service.current(EUI,"token",POT,0));assertEquals(2,available.get().getAsInt());
        fail.set(false);assertInstanceOf(NotFound.class,service.current(EUI,"token",POT,0));
        assertEquals(List.of("technical_failure","other"),outcomes);assertEquals(0,rejection.get());
    }
}
