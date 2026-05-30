package com.example.inventory.model;

import java.util.List;

public record InventoryLookupResponse(
        String authenticatedAs,
        String tokenIssuer,
        List<String> tokenAudience,
        List<InventoryItem> items
) {
}
