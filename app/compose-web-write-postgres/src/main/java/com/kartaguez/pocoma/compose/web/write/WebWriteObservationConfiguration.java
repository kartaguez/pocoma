package com.kartaguez.pocoma.compose.web.write;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.kartaguez.pocoma.infra.observability.micrometer.StageHandoffTracker;
@Configuration(proxyBeanMethods=false)
public class WebWriteObservationConfiguration {
@Bean(name="webWriteHandoffs") @ConditionalOnMissingBean(name="webWriteHandoffs")
StageHandoffTracker handoffs(){return new StageHandoffTracker();}
}
