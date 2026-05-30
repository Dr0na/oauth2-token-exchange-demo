package com.example.inventory.service;

import com.example.inventory.model.InventoryItem;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class InventoryService {

    private static final Map<String, InventoryItem> CATALOG = Map.of(
            "SKU-42", new InventoryItem("SKU-42", "Wireless Headphones", "East Coast DC", 37, "2026-06-10"),
            "SKU-17", new InventoryItem("SKU-17", "USB-C Dock", "East Coast DC", 4, "2026-06-03"),
            "SKU-99", new InventoryItem("SKU-99", "Mechanical Keyboard", "West Coast DC", 112, "In stock")
    );

    public List<InventoryItem> lookup(String productIds) {
        return Arrays.stream(productIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isBlank())
                .map(id -> CATALOG.getOrDefault(id, new InventoryItem(id, "Unknown product", "N/A", 0, "Unavailable")))
                .collect(Collectors.toList());
    }
}
