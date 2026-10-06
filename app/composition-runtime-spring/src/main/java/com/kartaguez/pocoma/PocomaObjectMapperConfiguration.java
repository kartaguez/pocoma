package com.kartaguez.pocoma;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

/** One JSON mapper shared by all deployment compositions in a JVM. */
@Configuration(proxyBeanMethods = false)
public class PocomaObjectMapperConfiguration {

    @Bean(name = {"webApiObjectMapper", "objectMapper"})
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper pocomaObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
