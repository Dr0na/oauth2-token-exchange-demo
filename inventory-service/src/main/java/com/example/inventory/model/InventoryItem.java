package com.example.inventory.model;

public record InventoryItem(
        String productId,
        String productName,
        String warehouse,
        int availableUnits,
        String restockEta
) {
}
