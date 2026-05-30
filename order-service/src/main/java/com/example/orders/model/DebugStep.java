package com.example.orders.model;

import java.util.Map;

public record DebugStep(
        int step,
        String title,
        String actor,
        String description,
        String status,
        HttpTrace request,
        HttpTrace response,
        Map<String, Object> decodedJwt,
        String notes,
        long durationMs
) {
}
