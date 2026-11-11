package com.kartaguez.pocoma.compose.web.write.v3;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.kartaguez.pocoma.infra.observability.micrometer.v3.StageHandoffTracker;
@Configuration(proxyBeanMethods=false)
public class WebWriteObservationConfiguration {
@Bean(name="webWriteHandoffs") @ConditionalOnMissingBean(name="webWriteHandoffs")
StageHandoffTracker handoffs(){return new StageHandoffTracker();}
}
