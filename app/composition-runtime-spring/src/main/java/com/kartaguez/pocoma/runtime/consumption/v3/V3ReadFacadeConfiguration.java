package com.kartaguez.pocoma.runtime.consumption.v3;

import com.kartaguez.pocoma.engine.read.pot.v3.ReadLimitsV3;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

import java.util.*;

@Configuration(proxyBeanMethods = false)
@Profile(V3ConsumptionWorkerSettings.READ_PROFILE)
public class V3ReadFacadeConfiguration {
    static ReadLimitsV3 limits(Environment env) {
        String p = "pocoma.v3.worker.read-limits.";
        var limits = new ReadLimitsV3(
                Integer.parseInt(env.getRequiredProperty(p + "expense-batch")),
                Integer.parseInt(env.getRequiredProperty(p + "navigation-page")),
                Long.parseLong(env.getRequiredProperty(p + "response-budget")));
        if (Arrays.asList(env.getActiveProfiles()).contains(V3ReadHttpConfiguration.PROFILE)
                && (limits.expenseBatch() != 50 || limits.navigationPage() != 100 || limits.responseBudget() != 16_000_000))
            throw new IllegalStateException("V3 HTTP requires explicit ratified initial READ limits");
        return limits;
    }

    @Bean
    V3ReadFacadeComposition.Services v3ReadServices(
            V3ConsumptionWorkerConfiguration.Resources resources,
            V3ConsumptionWorkerSettings settings,
            Environment env,
            Optional<MeterRegistry> meterRegistry) {
        return V3ReadFacadeComposition.create(
                resources.readBusiness,
                resources.readEffects,
                limits(env),
                new HashSet<>(settings.readParticipants()),
                () -> Optional.ofNullable(resources.worker).map(w -> w.snapshot()),
                Arrays.asList(env.getActiveProfiles()).contains(V3ReadHttpConfiguration.PROFILE),
                meterRegistry.orElse(null));
    }
}
