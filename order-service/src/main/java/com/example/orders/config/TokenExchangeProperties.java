package com.example.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "token-exchange")
public record TokenExchangeProperties(
        String tokenUrl,
        String clientId,
        String clientSecret,
        String subjectIssuer,
        String audience
) {
}
