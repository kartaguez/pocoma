package com.kartaguez.pocoma.runtime.consumption.v3;

import com.kartaguez.pocoma.engine.read.pot.v3.ReadLimitsV3;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

import java.util.*;

@Configuration(proxyBeanMethods = false)
@Profile(V3ConsumptionWorkerSettings.READ_PROFILE)
public class V3ReadFacadeConfiguration {
    static ReadLimitsV3 limits(Environment env) {
        String p = "pocoma.v3.worker.read-limits.";
        return new ReadLimitsV3(
                Integer.parseInt(env.getRequiredProperty(p + "expense-batch")),
                Integer.parseInt(env.getRequiredProperty(p + "navigation-page")),
                Long.parseLong(env.getRequiredProperty(p + "response-budget")));
    }

    @Bean
    V3ReadFacadeComposition.Services v3ReadServices(
            V3ConsumptionWorkerConfiguration.Resources resources,
            V3ConsumptionWorkerSettings settings,
            Environment env) {
        return V3ReadFacadeComposition.create(
                resources.readBusiness,
                resources.readEffects,
                limits(env),
                new HashSet<>(settings.readParticipants()),
                () -> Optional.ofNullable(resources.worker).map(w -> w.snapshot()));
    }
}
