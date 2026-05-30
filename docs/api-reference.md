# API reference

Base URLs (default Docker Compose):

| Service | Base URL |
|---------|----------|
| Order Service | `http://localhost:8080` |
| Inventory Service | `http://localhost:8081` |
| Keycloak Alpha | `http://localhost:8180` |
| Keycloak Beta | `http://localhost:8181` |

---

## Order Service

### `GET /api/demo/walkthrough/{orderId}`

Interactive walkthrough endpoint. Requires a **customer** access token from Keycloak Alpha.

**Headers**

```http
Authorization: Bearer <alpha-access-token>
Accept: application/json
```

**Response `200`**

```json
{
  "steps": [
    {
      "step": 3,
      "title": "Validate customer JWT",
      "actor": "Order Service (Spring Security OAuth2 Resource Server)",
      "description": "...",
      "status": "completed",
      "request": { "method": "INTERNAL", "url": "...", "headers": {}, "body": "..." },
      "response": { "method": "INTERNAL", "url": "...", "headers": {}, "body": "..." },
      "decodedJwt": { "iss": "...", "sub": "...", "preferred_username": "alice" },
      "notes": "...",
      "durationMs": 0
    }
  ],
  "result": { }
}
```

Steps 3–6 are returned in `steps`. Steps 1–2 are executed client-side in the browser UI.

**Errors**

| Status | Cause |
|--------|-------|
| `401` | Missing or invalid Alpha JWT |
| `404` | Unknown `orderId` |
| `502` | Token exchange or inventory call failed |

---

### `GET /api/orders/{orderId}`

Production-style fulfillment endpoint (no step trace). Same auth and result shape as walkthrough `result` object.

**Sample response fields**

```json
{
  "scenario": "Cross-IdP B2B token exchange",
  "message": "...",
  "order": {
    "orderId": "ORD-1001",
    "customerName": "Alice Customer",
    "status": "PROCESSING",
    "totalAmount": 249.97,
    "lineItems": [ { "productId": "SKU-42", "productName": "...", "quantity": 1 } ],
    "placedAt": "2026-05-28T14:30:00Z"
  },
  "inventory": {
    "authenticatedAs": "alice",
    "tokenIssuer": "http://localhost:8181/realms/warehouse",
    "tokenAudience": ["account", "inventory-api"],
    "items": [ { "productId": "SKU-42", "availableUnits": 37, "warehouse": "East Coast DC" } ]
  },
  "tokenExchange": {
    "sourceIssuer": "http://localhost:8180/realms/customer",
    "targetIssuer": "http://keycloak-beta:8181/realms/warehouse",
    "exchangedTokenAudience": "account,inventory-api",
    "exchangedForClient": "order-service-broker"
  },
  "requestedBy": "alice"
}
```

---

### `GET /actuator/health`

Spring Boot health check. No authentication.

---

## Inventory Service

### `GET /api/inventory?productIds={csv}`

Returns stock levels for comma-separated product IDs. Requires a **warehouse** access token from Keycloak Beta.

**Example**

```http
GET /api/inventory?productIds=SKU-42,SKU-17
Authorization: Bearer <beta-access-token>
```

**Response `200`**

```json
{
  "authenticatedAs": "alice",
  "tokenIssuer": "http://localhost:8181/realms/warehouse",
  "tokenAudience": ["account", "inventory-api"],
  "items": [
    {
      "productId": "SKU-42",
      "productName": "Wireless Headphones",
      "warehouse": "East Coast DC",
      "availableUnits": 37,
      "restockEta": "2026-06-10"
    }
  ]
}
```

---

## Keycloak Alpha — obtain customer token

```http
POST /realms/customer/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=password
&client_id=order-portal
&scope=openid profile email
&username=alice
&password=alice
```

---

## Keycloak Beta — token exchange (direct test)

```bash
curl -s -X POST 'http://localhost:8181/realms/warehouse/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'grant_type=urn:ietf:params:oauth:grant-type:token-exchange' \
  -d 'client_id=order-service-broker' \
  -d 'client_secret=order-service-broker-secret' \
  --data-urlencode "subject_token=${ALPHA_TOKEN}" \
  -d 'subject_token_type=urn:ietf:params:oauth:token-type:access_token' \
  -d 'subject_issuer=customer-idp' \
  -d 'requested_token_type=urn:ietf:params:oauth:token-type:access_token' \
  -d 'audience=inventory-api'
```
