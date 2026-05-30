package com.example.orders.service;

import com.example.orders.model.HttpTrace;
import com.example.orders.model.InventoryLookupResponse;
import com.example.orders.model.InventoryLookupTrace;
import com.example.orders.util.JwtDebugUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Service
public class InventoryClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final String inventoryBaseUrl;

    public InventoryClient(
            WebClient webClient,
            @Value("${inventory.service.url}") String inventoryBaseUrl) {
        this.webClient = webClient;
        this.inventoryBaseUrl = inventoryBaseUrl;
    }

    public InventoryLookupResponse lookupInventory(String warehouseAccessToken, String productIds) {
        return lookupWithTrace(warehouseAccessToken, productIds).body();
    }

    public InventoryLookupTrace lookupWithTrace(String warehouseAccessToken, String productIds) {
        String url = inventoryBaseUrl + "/api/inventory?productIds=" + productIds;
        HttpTrace requestTrace = new HttpTrace(
                "GET",
                url,
                Map.of(
                        "Authorization", "Bearer " + JwtDebugUtil.preview(warehouseAccessToken),
                        "Accept", "application/json"
                ),
                ""
        );

        long started = System.currentTimeMillis();
        InventoryLookupResponse body = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/inventory")
                        .queryParam("productIds", productIds)
                        .build())
                .headers(headers -> headers.setBearerAuth(warehouseAccessToken))
                .retrieve()
                .bodyToMono(InventoryLookupResponse.class)
                .block();
        long durationMs = System.currentTimeMillis() - started;

        String responseBody;
        try {
            responseBody = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(body);
        } catch (Exception ex) {
            responseBody = String.valueOf(body);
        }

        HttpTrace responseTrace = new HttpTrace(
                "GET",
                url,
                Map.of("Content-Type", "application/json"),
                responseBody
        );

        return new InventoryLookupTrace(body, requestTrace, responseTrace, durationMs);
    }
}
