package com.example.orders.model;

public record OrderFulfillmentResponse(
        String scenario,
        String message,
        Order order,
        InventoryLookupResponse inventory,
        TokenExchangeMetadata tokenExchange,
        String requestedBy
) {
}
