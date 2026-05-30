package com.example.orders.model;

import java.util.Map;

public record HttpTrace(
        String method,
        String url,
        Map<String, String> headers,
        String body
) {
}
