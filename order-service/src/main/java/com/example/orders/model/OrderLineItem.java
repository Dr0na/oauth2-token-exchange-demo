package com.example.orders.model;

public record OrderLineItem(
        String productId,
        String productName,
        int quantity
) {
}
