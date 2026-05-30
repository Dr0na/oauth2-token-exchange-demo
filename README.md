# OAuth2 Token Exchange — Flow Walkthrough

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-green)](https://spring.io/projects/spring-boot)
[![Keycloak](https://img.shields.io/badge/Keycloak-26.2-blue)](https://www.keycloak.org/)
[![RFC 8693](https://img.shields.io/badge/RFC-8693-lightgrey)](https://datatracker.ietf.org/doc/html/rfc8693)

An end-to-end, interactive demonstration of **cross-IdP OAuth 2.0 Token Exchange ([RFC 8693](https://datatracker.ietf.org/doc/html/rfc8693))** using two Keycloak identity providers and two Spring Boot microservices.

**Repository:** [github.com/Dr0na/oauth2-token-exchange-demo](https://github.com/Dr0na/oauth2-token-exchange-demo)

---

## Table of contents

- [Overview](#overview)
- [Why this demo exists](#why-this-demo-exists)
- [Architecture](#architecture)
- [Quick start](#quick-start)
- [Interactive walkthrough](#interactive-walkthrough)
- [API summary](#api-summary)
- [Project structure](#project-structure)
- [Documentation](#documentation)
- [Tear down](#tear-down)
- [Security notice](#security-notice)

---

## Overview

This project models a common enterprise integration pattern:

| Entity | Port | Role |
|--------|------|------|
| **Keycloak Alpha** | 8180 | Customer Portal IdP (`customer` realm) |
| **Keycloak Beta** | 8181 | Warehouse IdP (`warehouse` realm) |
| **Order Service** | 8080 | Customer-facing API; performs token exchange |
| **Inventory Service** | 8081 | Warehouse API; accepts only Beta-issued tokens |

**Customer Alice** logs into Keycloak Alpha, calls the Order Service with her token, and receives order details plus live warehouse inventory — even though the inventory API trusts a completely different IdP. The Order Service exchanges her token at Keycloak Beta before calling downstream.

---

## Why this demo exists

Real systems often span organizational or security boundaries:

- Customers authenticate against a **public/customer IdP**
- Internal APIs are protected by a **separate corporate IdP**
- A **BFF or integration service** must call internal APIs **on behalf of** the authenticated user

Token exchange solves this without:

- Sharing long-lived service credentials with the browser
- Issuing the customer a token with broad internal scopes
- Duplicating user directories across IdPs

This repo makes every hop **visible** — browser UI, CLI script, and traced server steps — so you can follow the flow like a debug session.

---

## Architecture

```
  Browser              Order Service           Keycloak Beta          Inventory Service
    │                        │                       │                        │
    │──① login (Alpha)──────>│                       │                        │
    │<── customer token ─────│                       │                        │
    │                        │                       │                        │
    │──② GET /api/demo/─────>│                       │                        │
    │   walkthrough          │──③ validate JWT       │                        │
    │                        │──④ token exchange ───>│                        │
    │                        │<── warehouse token ───│                        │
    │                        │──⑤ GET /inventory ───────────────────────────>│
    │                        │<────────────────────── stock data ────────────│
    │<──⑥ order + inventory─│                       │                        │
```

See [docs/architecture.md](docs/architecture.md) for component details, realm/client configuration, and Docker networking.

---

## Quick start

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) & Docker Compose v2
- (Optional) Java 21 + Maven 3.9 for local service development
- (Optional) `curl`, `python3` for the CLI walkthrough

### Start the stack

```bash
git clone https://github.com/Dr0na/oauth2-token-exchange-demo.git
cd oauth2-token-exchange-demo

docker compose up --build
```

First boot takes **~2 minutes** while Keycloak imports realms and the setup job configures token-exchange permissions.

Verify health:

```bash
docker compose ps
```

All services should be `running` or `healthy`; `keycloak-setup` should show `exited (0)`.

---

## Interactive walkthrough

### Browser (recommended)

Open **[http://localhost:8080](http://localhost:8080)**

| Control | Action |
|---------|--------|
| **Start** | Run step 1 |
| **Next step →** | Advance one hop at a time |
| **Run all** | Execute all 6 steps (optional auto-advance) |
| **Timeline (left)** | Jump back to any completed step |

The page shows an **architecture diagram** (left) and **step trace** (right): HTTP requests, responses, and decoded JWT claims.

**Demo credentials:** `alice` / `alice` · **Order ID:** `ORD-1001` or `ORD-1002`

### CLI

```bash
chmod +x scripts/demo.sh
./scripts/demo.sh                  # pause between steps
DEMO_AUTO=true ./scripts/demo.sh   # run continuously
```

### Manual curl

```bash
TOKEN=$(curl -s -X POST 'http://localhost:8180/realms/customer/protocol/openid-connect/token' \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d 'grant_type=password' \
  -d 'client_id=order-portal' \
  -d 'scope=openid profile email' \
  -d 'username=alice' \
  -d 'password=alice' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")

curl -s "http://localhost:8080/api/demo/walkthrough/ORD-1001" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

> **Note:** The `openid` scope is required so Keycloak Beta can validate the Alpha token via userinfo during exchange.

---

## API summary

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `GET` | `/` | — | Interactive flow walkthrough UI |
| `GET` | `/api/demo/walkthrough/{orderId}` | Alpha JWT | Full traced walkthrough (steps 3–6) |
| `GET` | `/api/orders/{orderId}` | Alpha JWT | Order fulfillment + inventory (production-style) |
| `GET` | `/api/inventory?productIds=` | Beta JWT | Warehouse stock lookup |

Full reference: [docs/api-reference.md](docs/api-reference.md)

---

## Project structure

```
oauth2-token-exchange-demo/
├── docker-compose.yml              # Full stack orchestration
├── keycloak-alpha/
│   └── realm-customer.json         # Customer realm (users, order-portal client)
├── keycloak-beta/
│   └── realm-warehouse.json        # Warehouse realm + customer-idp OIDC link
├── keycloak-setup/
│   └── Dockerfile                  # One-shot token-exchange permission setup
├── scripts/
│   ├── setup-token-exchange.sh     # Configures Keycloak FGAP v1 permissions
│   └── demo.sh                     # CLI step-by-step walkthrough
├── order-service/                  # Customer API + token exchange broker
├── inventory-service/              # Warehouse inventory API
└── docs/                           # Extended documentation
```

---

## Documentation

| Document | Contents |
|----------|----------|
| [docs/architecture.md](docs/architecture.md) | Components, realms, clients, networking |
| [docs/token-exchange.md](docs/token-exchange.md) | RFC 8693 flow, Keycloak setup, permissions |
| [docs/api-reference.md](docs/api-reference.md) | REST endpoints, schemas, examples |
| [docs/configuration.md](docs/configuration.md) | Environment variables, ports, secrets |
| [docs/troubleshooting.md](docs/troubleshooting.md) | Common errors and fixes |

---

## Tear down

```bash
docker compose down -v
```

---

## Security notice

This project is a **learning demo only**:

- Default passwords and client secrets are committed intentionally
- Resource Owner Password Credentials grant is used for simplicity
- Keycloak runs in `start-dev` mode

**Do not deploy this configuration to production.** Use Authorization Code + PKCE for users, managed secrets, hardened Keycloak, and [Standard Token Exchange V2](https://www.keycloak.org/securing-apps/token-exchange) where applicable.

---

## License

MIT — see [LICENSE](LICENSE).

---

## References

- [RFC 8693 — OAuth 2.0 Token Exchange](https://datatracker.ietf.org/doc/html/rfc8693)
- [Keycloak — Configuring and using token exchange](https://www.keycloak.org/securing-apps/token-exchange)
- [Keycloak 26.2 — Standard Token Exchange announcement](https://www.keycloak.org/2025/05/standard-token-exchange-kc-26-2)
