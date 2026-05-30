package com.example.orders.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Bean
    WebClient webClient(@Value("${inventory.service.url}") String inventoryServiceUrl) {
        return WebClient.builder()
                .baseUrl(inventoryServiceUrl)
                .build();
    }
}
