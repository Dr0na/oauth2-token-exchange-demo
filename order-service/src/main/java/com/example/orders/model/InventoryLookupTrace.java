package com.example.orders.model;

public record InventoryLookupTrace(
        InventoryLookupResponse body,
        HttpTrace request,
        HttpTrace response,
        long durationMs
) {
}
