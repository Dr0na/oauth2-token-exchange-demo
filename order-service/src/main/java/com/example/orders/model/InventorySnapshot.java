package com.example.orders.model;

public record InventorySnapshot(
        String productId,
        String productName,
        String warehouse,
        int availableUnits,
        String restockEta
) {
}
