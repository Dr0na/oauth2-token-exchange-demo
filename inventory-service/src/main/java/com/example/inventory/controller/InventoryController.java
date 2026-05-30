package com.example.inventory.controller;

import com.example.inventory.model.InventoryLookupResponse;
import com.example.inventory.service.InventoryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping
    public InventoryLookupResponse lookupInventory(
            @RequestParam String productIds,
            @AuthenticationPrincipal Jwt jwt) {
        return new InventoryLookupResponse(
                jwt.getClaimAsString("preferred_username"),
                jwt.getIssuer().toString(),
                extractAudience(jwt),
                inventoryService.lookup(productIds)
        );
    }

    @SuppressWarnings("unchecked")
    private List<String> extractAudience(Jwt jwt) {
        Object audience = jwt.getClaim("aud");
        if (audience instanceof String aud) {
            return List.of(aud);
        }
        if (audience instanceof List<?> audList) {
            return audList.stream().map(Object::toString).toList();
        }
        return Collections.emptyList();
    }
}
