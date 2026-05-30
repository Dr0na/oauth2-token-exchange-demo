package com.example.orders.model;

public record TokenExchangeMetadata(
        String sourceIssuer,
        String targetIssuer,
        String exchangedTokenAudience,
        String exchangedForClient
) {
}
