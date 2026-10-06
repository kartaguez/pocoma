package com.kartaguez.pocoma;

import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import com.kartaguez.pocoma.runtime.web.authentication.WebApiSecurityConfiguration;

/** HTTP composition shared by the distributed Web runtime and runtime-monolith. */
@Import({
        PocomaObjectMapperConfiguration.class,
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
    TraceCorrelationFilter traceCorrelationFilter() {
        return new TraceCorrelationFilter();
    }
}
