package com.kartaguez.pocoma;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.runtime.web.authentication.WebApiSecurityConfiguration;

/** HTTP composition shared by the distributed Web runtime and runtime-monolith. */
@Import({
        CommandAdmissionConfiguration.class,
        CommandResultReadConfiguration.class,
        OpenApiConfiguration.class,
        PotReadConfiguration.class,
        ProjectionReadConfiguration.class,
        RegistrationAdmissionConfiguration.class,
        RegistrationResultReadConfiguration.class,
        WebAuthorizationConfiguration.class,
        WebApiSecurityConfiguration.class
})
public class PocomaWebRuntimeConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ObjectMapper webApiObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    TraceCorrelationFilter traceCorrelationFilter() {
        return new TraceCorrelationFilter();
    }
}
