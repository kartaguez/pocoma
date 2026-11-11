package com.kartaguez.pocoma.compose.polling.process.v3;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.kartaguez.pocoma.infra.observability.micrometer.v3.StageHandoffTracker;
@Configuration(proxyBeanMethods=false)
public class ProcessObservationConfiguration {
@Bean(name="processHandoffs") @ConditionalOnMissingBean(name="processHandoffs")
StageHandoffTracker handoffs(){return new StageHandoffTracker();}
}
