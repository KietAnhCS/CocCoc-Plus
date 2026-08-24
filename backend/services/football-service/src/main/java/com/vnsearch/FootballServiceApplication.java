package com.vnsearch;

import com.vnsearch.config.PublicEndpoints;
import com.vnsearch.football.config.FootballProperties;
import com.vnsearch.football.service.FootballService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
@EnableConfigurationProperties(FootballProperties.class)
public class FootballServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FootballServiceApplication.class, args);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PublicEndpoints footballPublicEndpoints() {
        return PublicEndpoints.of("/api/v1/**", "/v3/api-docs/**", "/swagger-ui/**",
                "/swagger-ui.html");
    }

    @Bean
    public ApplicationRunner restoreApiKey(FootballService service) {
        return args -> service.restoreApiKey();
    }

    @Bean
    public ApplicationRunner footballMetrics(MeterRegistry registry, FootballService service) {
        return args -> {
            registry.gauge("football.api.calls.today", service, gauge -> {
                try {
                    return gauge.used();
                } catch (RuntimeException unreadable) {
                    return -1;
                }
            });
            registry.gauge("football.api.daily.budget", service, FootballService::budget);
            registry.gauge("football.sample.mode", service,
                    gauge -> gauge.hasApiKey() ? 0 : 1);
        };
    }
}
