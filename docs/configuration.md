# Configuration

## Ports

| Port | Service |
|------|---------|
| 8080 | Order Service |
| 8081 | Inventory Service |
| 8180 | Keycloak Alpha |
| 8181 | Keycloak Beta |

## Default credentials

| System | Username | Password |
|--------|----------|----------|
| Keycloak admin (both) | `admin` | `admin` |
| Demo customer | `alice` | `alice` |
| Warehouse user (unused in walkthrough) | `bob` | `bob` |

## Client secrets (demo only)

| Client | Secret |
|--------|--------|
| `order-service-broker` | `order-service-broker-secret` |
| `inventory-api` | `inventory-api-secret` |
| `token-exchange-validator` | `token-exchange-validator-secret` |

## Order Service environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `KEYCLOAK_ALPHA_ISSUER` | `http://localhost:8180/realms/customer` | Expected JWT issuer |
| `KEYCLOAK_ALPHA_JWK_SET_URI` | Alpha JWKS URL | Internal URL for signature verification |
| `KEYCLOAK_BETA_TOKEN_URL` | Beta token endpoint | Token exchange POST target |
| `INVENTORY_SERVICE_URL` | `http://inventory-service:8081` | Downstream inventory base URL |
| `TOKEN_EXCHANGE_CLIENT_ID` | `order-service-broker` | Exchange client ID |
| `TOKEN_EXCHANGE_CLIENT_SECRET` | `order-service-broker-secret` | Exchange client secret |
| `TOKEN_EXCHANGE_SUBJECT_ISSUER` | `customer-idp` | Beta IdP alias for Alpha |
| `TOKEN_EXCHANGE_AUDIENCE` | `inventory-api` | Requested token audience |

## Inventory Service environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `KEYCLOAK_BETA_ISSUER` | `http://localhost:8181/realms/warehouse` | Expected JWT issuer |
| `KEYCLOAK_BETA_JWK_SET_URI` | Beta JWKS URL | Internal URL for signature verification |

## keycloak-setup environment variables

| Variable | Default |
|----------|---------|
| `KEYCLOAK_ALPHA_URL` | `http://keycloak-alpha:8180` |
| `KEYCLOAK_BETA_URL` | `http://keycloak-beta:8181` |
| `KEYCLOAK_BETA_REALM` | `warehouse` |
| `TOKEN_EXCHANGE_SUBJECT_ISSUER` | `customer-idp` |
| `TOKEN_EXCHANGE_CLIENT_ID` | `order-service-broker` |

## CLI demo environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DEMO_AUTO` | `false` | Skip Enter prompts |
| `DEMO_PAUSE` | `true` | Wait between steps |
| `DEMO_ORDER_ID` | `ORD-1001` | Order to look up |
| `DEMO_USERNAME` / `DEMO_PASSWORD` | `alice` / `alice` | Login credentials |
| `KEYCLOAK_ALPHA_URL` | `http://localhost:8180` | Alpha base URL |
| `ORDER_SERVICE_URL` | `http://localhost:8080` | Order service base URL |

## Local development (without full Compose)

```bash
# Start IdPs + permission setup only
docker compose up keycloak-alpha keycloak-beta keycloak-setup

# Inventory service
cd inventory-service
KEYCLOAK_BETA_ISSUER=http://localhost:8181/realms/warehouse \
KEYCLOAK_BETA_JWK_SET_URI=http://localhost:8181/realms/warehouse/protocol/openid-connect/certs \
mvn spring-boot:run

# Order service (separate terminal)
cd order-service
KEYCLOAK_ALPHA_ISSUER=http://localhost:8180/realms/customer \
KEYCLOAK_ALPHA_JWK_SET_URI=http://localhost:8180/realms/customer/protocol/openid-connect/certs \
KEYCLOAK_BETA_TOKEN_URL=http://localhost:8181/realms/warehouse/protocol/openid-connect/token \
INVENTORY_SERVICE_URL=http://localhost:8081 \
mvn spring-boot:run
```

Requires Java 21 and Maven 3.9+.
