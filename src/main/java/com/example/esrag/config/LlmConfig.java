package com.example.esrag.config;

import ai.z.openapi.ZhipuAiClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

@Configuration
public class LlmConfig {

    @Value("${llm.api-key}")
    private String apiKey;

    @Bean
    public ZhipuAiClient zhipuAiClient() {
        return ZhipuAiClient.builder().ofZHIPU()
                .apiKey(apiKey)
                .build();
    }
}
