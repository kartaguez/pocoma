package com.kartaguez.pocoma.compose.polling.materialize.v3;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.kartaguez.pocoma.infra.observability.micrometer.v3.StageHandoffTracker;
@Configuration(proxyBeanMethods=false)
public class MaterializeObservationConfiguration {
@Bean(name="materializeHandoffs") @ConditionalOnMissingBean(name="materializeHandoffs")
StageHandoffTracker handoffs(){return new StageHandoffTracker();}
}
