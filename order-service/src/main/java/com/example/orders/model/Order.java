package com.example.orders.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record Order(
        String orderId,
        String customerName,
        String status,
        BigDecimal totalAmount,
        List<OrderLineItem> lineItems,
        Instant placedAt
) {
}
