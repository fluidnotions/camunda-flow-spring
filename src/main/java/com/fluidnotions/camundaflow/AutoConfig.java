package com.fluidnotions.camundaflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

@Configuration
@AutoConfiguration
public class AutoConfig {
    @Bean
    @ConditionalOnMissingBean
    public Jackson2ObjectMapperBuilder jacksonBuilder() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        return builder;
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder.build();
    }

    @Bean
    public CamundaSubscriptionInitializer camundaSubscriptionInitializer(ObjectMapper objectMapper) {
        return new CamundaSubscriptionInitializer(objectMapper);
    }
}
