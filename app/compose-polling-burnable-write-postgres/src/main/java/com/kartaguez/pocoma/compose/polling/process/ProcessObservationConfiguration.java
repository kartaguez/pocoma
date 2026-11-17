package com.kartaguez.pocoma.compose.polling.process;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.kartaguez.pocoma.infra.observability.micrometer.StageHandoffTracker;
@Configuration(proxyBeanMethods=false)
public class ProcessObservationConfiguration {
@Bean(name="processHandoffs") @ConditionalOnMissingBean(name="processHandoffs")
StageHandoffTracker handoffs(){return new StageHandoffTracker();}
}
