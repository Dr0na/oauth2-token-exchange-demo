package com.example.orders.service;

import com.example.orders.model.InventoryLookupResponse;
import com.example.orders.model.Order;
import com.example.orders.model.OrderFulfillmentResponse;
import com.example.orders.model.OrderLineItem;
import com.example.orders.model.TokenExchangeMetadata;
import com.example.orders.model.TokenExchangeResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private static final Map<String, Order> SAMPLE_ORDERS = Map.of(
            "ORD-1001", new Order(
                    "ORD-1001",
                    "Alice Customer",
                    "PROCESSING",
                    new BigDecimal("249.97"),
                    List.of(
                            new OrderLineItem("SKU-42", "Wireless Headphones", 1),
                            new OrderLineItem("SKU-17", "USB-C Dock", 2)
                    ),
                    Instant.parse("2026-05-28T14:30:00Z")
            ),
            "ORD-1002", new Order(
                    "ORD-1002",
                    "Alice Customer",
                    "SHIPPED",
                    new BigDecimal("89.99"),
                    List.of(new OrderLineItem("SKU-99", "Mechanical Keyboard", 1)),
                    Instant.parse("2026-05-27T09:15:00Z")
            )
    );

    private final TokenExchangeService tokenExchangeService;
    private final InventoryClient inventoryClient;
    private final String betaIssuer;

    public OrderService(
            TokenExchangeService tokenExchangeService,
            InventoryClient inventoryClient,
            @Value("${token-exchange.token-url}") String betaTokenUrl) {
        this.tokenExchangeService = tokenExchangeService;
        this.inventoryClient = inventoryClient;
        this.betaIssuer = betaTokenUrl.replace("/protocol/openid-connect/token", "");
    }

    public Order findOrder(String orderId) {
        Order order = SAMPLE_ORDERS.get(orderId);
        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }
        return order;
    }

    public OrderFulfillmentResponse fulfillOrder(String orderId, Jwt customerJwt, String rawAccessToken) {
        Order order = findOrder(orderId);
        TokenExchangeResult exchange = tokenExchangeService.exchangeWithTrace(rawAccessToken);

        String productIds = order.lineItems().stream()
                .map(OrderLineItem::productId)
                .collect(Collectors.joining(","));

        InventoryLookupResponse inventory = inventoryClient.lookupInventory(exchange.accessToken(), productIds);
        return buildFulfillmentResponse(order, customerJwt, inventory, exchange);
    }

    public OrderFulfillmentResponse buildFulfillmentResponse(
            Order order,
            Jwt customerJwt,
            InventoryLookupResponse inventory,
            TokenExchangeResult exchange) {
        TokenExchangeMetadata exchangeMetadata = new TokenExchangeMetadata(
                customerJwt.getIssuer().toString(),
                betaIssuer,
                inventory.tokenAudience() != null && !inventory.tokenAudience().isEmpty()
                        ? String.join(",", inventory.tokenAudience())
                        : "inventory-api",
                "order-service-broker"
        );

        return new OrderFulfillmentResponse(
                "Cross-IdP B2B token exchange",
                "Customer token from the Customer Portal IdP was exchanged for a Warehouse IdP token "
                        + "so this order service could call the inventory API on the user's behalf.",
                order,
                inventory,
                exchangeMetadata,
                customerJwt.getClaimAsString("preferred_username")
        );
    }
}
