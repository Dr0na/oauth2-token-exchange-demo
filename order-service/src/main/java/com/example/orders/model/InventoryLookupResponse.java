package com.example.orders.model;

import java.util.List;

public record InventoryLookupResponse(
        String authenticatedAs,
        String tokenIssuer,
        List<String> tokenAudience,
        List<InventorySnapshot> items
) {
}
