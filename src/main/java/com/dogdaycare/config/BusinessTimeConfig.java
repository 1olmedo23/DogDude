package com.dogdaycare.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class BusinessTimeConfig {

    @Bean
    public ZoneId businessZoneId(@Value("${app.business.zone:America/Los_Angeles}") String zone) {
        return ZoneId.of(zone);
    }

    @Bean
    public Clock businessClock(ZoneId businessZoneId) {
        return Clock.system(businessZoneId);
    }
}
