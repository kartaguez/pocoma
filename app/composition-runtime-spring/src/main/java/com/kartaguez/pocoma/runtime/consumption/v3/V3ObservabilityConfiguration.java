package com.kartaguez.pocoma.runtime.consumption.v3;
import com.kartaguez.pocoma.infra.observability.micrometer.v3.StageHandoffTracker;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
public class V3ObservabilityConfiguration {
@Bean StageHandoffTracker stageHandoffTracker() {return new StageHandoffTracker();}
}
