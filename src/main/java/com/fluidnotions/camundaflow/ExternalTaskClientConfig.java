package com.fluidnotions.camundaflow;

import org.camunda.bpm.client.ExternalTaskClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExternalTaskClientConfig {

    @Value("${camunda.bpm.client.base-url}")
    private String basePath;

    @Bean
    public ExternalTaskClient taskClient() {
        return ExternalTaskClient.create().baseUrl(basePath).asyncResponseTimeout(10000).build();
    }
}
