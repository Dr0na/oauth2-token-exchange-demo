# Architecture

## System context

The demo simulates a **customer order portal** (external) calling a **warehouse inventory system** (internal), each protected by its own Keycloak instance.

```
┌─────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   Browser   │────▶│  Order Service   │────▶│ Inventory Svc   │
│   (Alice)   │     │     :8080        │     │     :8081       │
└──────┬──────┘     └────────┬─────────┘     └────────┬────────┘
       │                     │                         │
       │ login               │ token exchange          │ validate
       ▼                     ▼                         ▼
┌─────────────┐     ┌──────────────────┐     (same Keycloak Beta)
│ Keycloak    │     │ Keycloak Beta    │
│ Alpha :8180 │     │ :8181            │
│ customer    │     │ warehouse        │
└─────────────┘     └──────────────────┘
                           ▲
                           │ trusts Alpha as
                           │ OIDC IdP (customer-idp)
```

## Components

### Keycloak Alpha — Customer Portal IdP

| Setting | Value |
|---------|-------|
| Image | `quay.io/keycloak/keycloak:26.2.5` |
| Port | `8180` |
| Realm | `customer` |
| Issuer | `http://localhost:8180/realms/customer` |

**Clients**

| Client ID | Type | Purpose |
|-----------|------|---------|
| `order-portal` | Public | Browser/CLI login; issues customer access tokens |
| `token-exchange-validator` | Confidential | Used by Beta IdP to validate Alpha tokens (userinfo) |

**Users**

| Username | Password | Role |
|----------|----------|------|
| `alice` | `alice` | `customer` |

### Keycloak Beta — Warehouse IdP

| Setting | Value |
|---------|-------|
| Port | `8181` |
| Realm | `warehouse` |
| Issuer | `http://localhost:8181/realms/warehouse` |

**Clients**

| Client ID | Type | Purpose |
|-----------|------|---------|
| `inventory-api` | Bearer-only | Audience for exchanged tokens; protects inventory API |
| `order-service-broker` | Confidential | Performs RFC 8693 token exchange on behalf of Order Service |

**Identity provider**

| Alias | Type | Points to |
|-------|------|-----------|
| `customer-idp` | OIDC | Keycloak Alpha `customer` realm |

Beta validates incoming Alpha tokens by calling Alpha's userinfo endpoint (requires `openid` scope on the customer token).

### Order Service

Spring Boot 3.4 / Java 21 OAuth2 **resource server** (validates Alpha JWTs).

Responsibilities:

1. Validate customer Bearer token (issuer + JWKS from Alpha)
2. Exchange token at Beta token endpoint (`subject_issuer=customer-idp`)
3. Call Inventory Service with exchanged token
4. Return merged order + inventory response

Key classes:

- `TokenExchangeService` — POST to Beta `/protocol/openid-connect/token`
- `WalkthroughService` — builds per-step debug trace for the UI
- `DemoController` — `/api/demo/walkthrough/{orderId}`

### Inventory Service

Spring Boot OAuth2 resource server validating **Beta** JWTs only.

Sample catalog: `SKU-42`, `SKU-17`, `SKU-99`.

### keycloak-setup (one-shot job)

After both Keycloaks are healthy, this container:

1. Enables fine-grained admin permissions (FGAP v1) on `warehouse` realm
2. Enables IdP permissions on `customer-idp`
3. Creates client policy allowing `order-service-broker` to exchange tokens
4. Binds policies to IdP and `inventory-api` token-exchange permissions

Implemented in `scripts/setup-token-exchange.sh`.

## Docker networking

Services communicate on the Compose network using internal hostnames (`keycloak-alpha`, `keycloak-beta`, etc.).

JWT **issuer** claims use `http://localhost:8180` / `8181` (browser-facing). Spring services therefore configure:

- `issuer-uri` → `localhost` (matches JWT `iss` claim)
- `jwk-set-uri` → internal hostname (reachable from containers)

## Walkthrough steps (1–6)

| Step | Actor | Action |
|------|-------|--------|
| 1 | Browser → Alpha | Obtain customer access token |
| 2 | Browser → Order Service | `GET /api/demo/walkthrough/{orderId}` |
| 3 | Order Service | Validate JWT against Alpha JWKS |
| 4 | Order Service → Beta | RFC 8693 token exchange |
| 5 | Order Service → Inventory | `GET /api/inventory` with warehouse token |
| 6 | Order Service → Browser | Assemble fulfillment JSON |

## Sample data

**Orders** (Order Service)

| Order ID | Status | Products |
|----------|--------|----------|
| `ORD-1001` | PROCESSING | SKU-42, SKU-17 |
| `ORD-1002` | SHIPPED | SKU-99 |

**Inventory** (Inventory Service)

| SKU | Product | Warehouse | Qty |
|-----|---------|-----------|-----|
| SKU-42 | Wireless Headphones | East Coast DC | 37 |
| SKU-17 | USB-C Dock | East Coast DC | 4 |
| SKU-99 | Mechanical Keyboard | West Coast DC | 112 |
