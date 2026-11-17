package com.kartaguez.pocoma.compose.polling.materialize;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.kartaguez.pocoma.infra.observability.micrometer.StageHandoffTracker;
@Configuration(proxyBeanMethods=false)
public class MaterializeObservationConfiguration {
@Bean(name="materializeHandoffs") @ConditionalOnMissingBean(name="materializeHandoffs")
StageHandoffTracker handoffs(){return new StageHandoffTracker();}
}
