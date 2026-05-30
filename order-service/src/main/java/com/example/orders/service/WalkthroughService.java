package com.example.orders.service;

import com.example.orders.model.DebugStep;
import com.example.orders.model.HttpTrace;
import com.example.orders.model.InventoryLookupResponse;
import com.example.orders.model.InventoryLookupTrace;
import com.example.orders.model.Order;
import com.example.orders.model.OrderFulfillmentResponse;
import com.example.orders.model.OrderLineItem;
import com.example.orders.model.TokenExchangeMetadata;
import com.example.orders.model.TokenExchangeResult;
import com.example.orders.model.WalkthroughResponse;
import com.example.orders.util.JwtDebugUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class WalkthroughService {

    private final OrderService orderService;
    private final TokenExchangeService tokenExchangeService;
    private final InventoryClient inventoryClient;
    private final String alphaIssuer;
    private final String alphaJwkSetUri;
    private final String betaIssuer;

    public WalkthroughService(
            OrderService orderService,
            TokenExchangeService tokenExchangeService,
            InventoryClient inventoryClient,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String alphaIssuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String alphaJwkSetUri,
            @Value("${token-exchange.token-url}") String betaTokenUrl) {
        this.orderService = orderService;
        this.tokenExchangeService = tokenExchangeService;
        this.inventoryClient = inventoryClient;
        this.alphaIssuer = alphaIssuer;
        this.alphaJwkSetUri = alphaJwkSetUri;
        this.betaIssuer = betaTokenUrl.replace("/protocol/openid-connect/token", "");
    }

    public WalkthroughResponse walkthrough(String orderId, Jwt customerJwt, String rawAccessToken, String walkthroughUrl) {
        Order order = orderService.findOrder(orderId);
        List<DebugStep> steps = new ArrayList<>();

        steps.add(step3ValidateCustomerToken(customerJwt, rawAccessToken));
        TokenExchangeResult exchange = tokenExchangeService.exchangeWithTrace(rawAccessToken);
        steps.add(step4TokenExchange(exchange));

        String productIds = order.lineItems().stream()
                .map(OrderLineItem::productId)
                .collect(Collectors.joining(","));
        InventoryLookupTrace inventoryTrace = inventoryClient.lookupWithTrace(exchange.accessToken(), productIds);
        steps.add(step5InventoryLookup(inventoryTrace));

        OrderFulfillmentResponse result = orderService.buildFulfillmentResponse(
                order,
                customerJwt,
                inventoryTrace.body(),
                exchange
        );
        steps.add(step6AssembleResponse(walkthroughUrl, result));

        return new WalkthroughResponse(steps, result);
    }

    private DebugStep step3ValidateCustomerToken(Jwt customerJwt, String rawAccessToken) {
        return new DebugStep(
                3,
                "Validate customer JWT",
                "Order Service (Spring Security OAuth2 Resource Server)",
                "The order service validates the incoming Bearer token: signature checked against "
                        + "Keycloak Alpha JWKS, issuer must match " + alphaIssuer + ", and expiry is enforced.",
                "completed",
                new HttpTrace(
                        "INTERNAL",
                        alphaJwkSetUri,
                        Map.of("Authorization", "Bearer " + JwtDebugUtil.preview(rawAccessToken)),
                        "JWT validation against issuer " + alphaIssuer
                ),
                new HttpTrace(
                        "INTERNAL",
                        "order-service/security",
                        Map.of("authenticated-user", customerJwt.getClaimAsString("preferred_username")),
                        "Authentication successful — request proceeds to token exchange"
                ),
                JwtDebugUtil.claimsFromJwt(customerJwt),
                "At this point the service trusts the caller is customer '"
                        + customerJwt.getClaimAsString("preferred_username")
                        + "' but the token is scoped for the customer portal, not the warehouse API.",
                0L
        );
    }

    private DebugStep step4TokenExchange(TokenExchangeResult exchange) {
        return new DebugStep(
                4,
                "Exchange token at Warehouse IdP (RFC 8693)",
                "Order Service → Keycloak Beta",
                "The order-service-broker client submits the customer token to Keycloak Beta using "
                        + "grant_type=urn:ietf:params:oauth:grant-type:token-exchange. Beta validates the token "
                        + "via the customer-idp identity provider (userinfo call to Alpha) and mints a new token "
                        + "scoped for inventory-api.",
                "completed",
                exchange.request(),
                exchange.response(),
                exchange.decodedAccessToken(),
                "subject_issuer=customer-idp tells Beta to treat the subject_token as an external token from Alpha. "
                        + "The returned warehouse token carries audience inventory-api — least privilege for the downstream call.",
                exchange.durationMs()
        );
    }

    private DebugStep step5InventoryLookup(InventoryLookupTrace inventoryTrace) {
        InventoryLookupResponse inventory = inventoryTrace.body();
        return new DebugStep(
                5,
                "Call warehouse inventory API",
                "Order Service → Inventory Service",
                "The exchanged warehouse token is forwarded to the inventory service. "
                        + "Inventory service validates the token against Keycloak Beta and returns stock levels.",
                "completed",
                inventoryTrace.request(),
                inventoryTrace.response(),
                Map.of(
                        "iss", inventory.tokenIssuer(),
                        "aud", inventory.tokenAudience(),
                        "preferred_username", inventory.authenticatedAs()
                ),
                "Inventory service accepted the exchanged token — proof that cross-IdP token exchange succeeded.",
                inventoryTrace.durationMs()
        );
    }

    private DebugStep step6AssembleResponse(String walkthroughUrl, OrderFulfillmentResponse result) {
        return new DebugStep(
                6,
                "Assemble fulfillment response",
                "Order Service",
                "Order details from the order service are merged with live inventory data from the warehouse API.",
                "completed",
                new HttpTrace("INTERNAL", "order-service/aggregate", Map.of(), "Merge order + inventory + tokenExchange metadata"),
                new HttpTrace("GET", walkthroughUrl, Map.of("Content-Type", "application/json"), "200 OK — walkthrough complete"),
                Map.of(
                        "sourceIssuer", result.tokenExchange().sourceIssuer(),
                        "targetIssuer", result.tokenExchange().targetIssuer(),
                        "requestedBy", result.requestedBy()
                ),
                "The customer sees a single response. Token exchange happened transparently on the server.",
                0L
        );
    }
}
