package com.kartaguez.pocoma.infra.observability.micrometer.v3;
import com.kartaguez.pocoma.contract.readquery.ReadObservation;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.IntSupplier;
/** Scoped READ metrics, without execution authority. */
public final class MicrometerReadObservation implements ReadObservation {
    private final MeterRegistry registry;
    private final Counter rejections;
    public MicrometerReadObservation(MeterRegistry registry) {
        this.registry=registry; this.rejections=registry==null?null:registry.counter("pocoma.v3.read.quota.rejections","scope","sql");
    }
    public void quota(IntSupplier available) {
        if(registry!=null) Gauge.builder("pocoma.v3.read.quota.permits",available,IntSupplier::getAsInt).tag("scope","sql").register(registry);
    }
    public void rejected() { if(rejections!=null) rejections.increment(); }
    public void record(String operation,String outcome,long elapsedNanos) {
        if(registry!=null) Timer.builder("pocoma.v3.read.query.duration").tags("operation",operation,"outcome",outcome)
            .publishPercentileHistogram().serviceLevelObjectives(BUCKETS).register(registry).record(Duration.ofNanos(elapsedNanos));
    }
        private static final Duration[] BUCKETS = {
            Duration.ofMillis(1),
            Duration.ofMillis(5),
            Duration.ofMillis(10),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            Duration.ofSeconds(120)
        };

}
