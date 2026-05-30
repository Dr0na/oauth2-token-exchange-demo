package com.example.orders.model;

import java.util.Map;

public record TokenExchangeResult(
        String accessToken,
        HttpTrace request,
        HttpTrace response,
        Map<String, Object> decodedAccessToken,
        long durationMs
) {
}
