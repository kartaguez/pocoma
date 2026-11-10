package com.kartaguez.pocoma;

import com.kartaguez.pocoma.runtime.consumption.v3.V3ReadHttpConfiguration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.*;

@SpringBootApplication(scanBasePackages = "com.kartaguez.pocoma.runtime.bootstrap")
@Import({PocomaWebApiApplication.Legacy.class, V3ReadHttpConfiguration.class,
    com.kartaguez.pocoma.runtime.consumption.v3.V3AdmissionHttpConfiguration.class})
public class PocomaWebApiApplication {
    public static void main(String[] args) { SpringApplication.run(PocomaWebApiApplication.class, args); }
    @Configuration(proxyBeanMethods = false)
    @Profile("!v3-http-read & !v3-http-admission")
    @ComponentScan(basePackages = "com.kartaguez.pocoma", excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = org.springframework.boot.context.TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter.class)
    })
    @Import(PocomaWebRuntimeConfiguration.class)
    public static class Legacy {}
}
