# Troubleshooting

## Startup

### `keycloak-setup` exits with code 2

**Symptom:** `Unknown option: '/opt/keycloak/setup-token-exchange.sh'`

The setup container must use `bash` as entrypoint, not Keycloak's default `kc.sh`. Ensure `keycloak-setup/Dockerfile` contains:

```dockerfile
ENTRYPOINT ["/bin/bash", "/opt/keycloak/setup-token-exchange.sh"]
```

### `Feature not enabled` during setup

**Symptom:** Setup fails when enabling admin permissions.

Keycloak Beta must start with FGAP v1:

```yaml
--features=token-exchange:v1,admin-fine-grained-authz:v1
```

Recreate Keycloak containers after changing features:

```bash
docker compose up -d --force-recreate keycloak-alpha keycloak-beta
docker compose run --rm keycloak-setup
```

### Services stuck waiting for Keycloak

First boot can take 60–90 seconds. Check logs:

```bash
docker compose logs keycloak-alpha keycloak-beta
```

---

## Token exchange

### `403` — `Client not allowed to exchange`

Token-exchange permissions are missing or incomplete.

```bash
docker compose run --rm keycloak-setup
```

Verify IdP permissions in Beta admin console: **Identity providers → customer-idp → Permissions**.

### `400` — `invalid_token` / `user info call failure`

The Alpha access token is missing the **`openid` scope**.

Always request:

```text
scope=openid profile email
```

Verify userinfo manually:

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  http://localhost:8180/realms/customer/protocol/openid-connect/userinfo
```

Expected: `200` with user claims. If you see `insufficient_scope`, re-request the token with `openid`.

---

## Order Service

### `401 Unauthorized`

| Cause | Fix |
|-------|-----|
| Token expired (5 min default) | Request a fresh token |
| Wrong issuer | Token must be from Alpha `customer` realm |
| Clock skew | Sync system time |

### `500 Internal Server Error` on walkthrough

Check order service logs:

```bash
docker compose logs order-service --tail 50
```

Common causes: token exchange failure (above) or inventory service unreachable.

### JWT validation — `Connection refused` to localhost:8180

Inside Docker, the Order Service cannot reach Keycloak at `localhost`. Ensure `jwk-set-uri` uses the internal hostname (`keycloak-alpha:8180`) while `issuer-uri` remains `localhost` (matches JWT `iss` claim). This is pre-configured in `docker-compose.yml`.

---

## Inventory Service

### `401` when called directly with Alpha token

Expected behavior. The inventory API only accepts **Beta**-issued tokens. Use the Order Service or perform token exchange first.

---

## Browser UI

### CORS errors calling Keycloak Alpha from localhost:8080

The demo page calls Alpha's token endpoint directly. Keycloak realm `order-portal` client has `webOrigins: ["+"]` to allow this in dev mode.

### Diagram / walkthrough not updating

Hard-refresh the page (`Cmd+Shift+R`) after rebuilding:

```bash
docker compose up --build order-service
```

---

## Reset everything

```bash
docker compose down -v
docker compose up --build
```

This removes volumes and re-imports realms from scratch.
