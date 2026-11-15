package com.kartaguez.pocoma.compose.web.read;
import org.springframework.context.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.kartaguez.pocoma.infra.observability.micrometer.StageHandoffTracker;
@Configuration(proxyBeanMethods=false)
public class WebReadObservationConfiguration {
@Bean(name="webReadHandoffs") @ConditionalOnMissingBean(name="webReadHandoffs")
StageHandoffTracker handoffs(){return new StageHandoffTracker();}
}
