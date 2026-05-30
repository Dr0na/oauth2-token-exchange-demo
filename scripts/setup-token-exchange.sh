#!/usr/bin/env bash
set -euo pipefail

ALPHA_URL="${KEYCLOAK_ALPHA_URL:-http://keycloak-alpha:8180}"
BETA_URL="${KEYCLOAK_BETA_URL:-http://keycloak-beta:8181}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASSWORD="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
REALM_BETA="${KEYCLOAK_BETA_REALM:-warehouse}"
IDP_ALIAS="${TOKEN_EXCHANGE_SUBJECT_ISSUER:-customer-idp}"
BROKER_CLIENT="${TOKEN_EXCHANGE_CLIENT_ID:-order-service-broker}"

json_field() {
  local json=$1
  local field=$2
  echo "${json}" | tr -d '\n' | sed -n "s/.*\"${field}\"[[:space:]]*:[[:space:]]*\"\\([^\"]*\\)\".*/\\1/p" | head -n 1
}

retry() {
  local attempts=$1
  local delay=$2
  shift 2
  local attempt=1
  while true; do
    if "$@"; then
      return 0
    fi
    if (( attempt >= attempts )); then
      echo "Command failed after ${attempts} attempts: $*" >&2
      return 1
    fi
    echo "Attempt ${attempt}/${attempts} failed; retrying in ${delay}s..."
    sleep "${delay}"
    ((attempt++))
  done
}

wait_for_url() {
  local url=$1
  local name=$2
  local host=$3
  local port=$4
  local path=$5
  echo "Waiting for ${name} at ${url}..."
  until bash -c "exec 3<>/dev/tcp/${host}/${port} && echo -e 'GET ${path} HTTP/1.1\\r\\nHost: ${host}\\r\\nConnection: close\\r\\n\\r\\n' >&3 && cat <&3 | grep -q '200 OK'"; do
    sleep 2
  done
}

wait_for_url "${ALPHA_URL}/realms/customer/.well-known/openid-configuration" "Keycloak Alpha" "keycloak-alpha" "8180" "/realms/customer/.well-known/openid-configuration"
wait_for_url "${BETA_URL}/realms/${REALM_BETA}/.well-known/openid-configuration" "Keycloak Beta" "keycloak-beta" "8181" "/realms/${REALM_BETA}/.well-known/openid-configuration"

echo "Configuring token exchange permissions on Beta realm..."

/opt/keycloak/bin/kcadm.sh config credentials \
  --server "${BETA_URL}" \
  --realm master \
  --user "${ADMIN_USER}" \
  --password "${ADMIN_PASSWORD}"

echo "Enabling fine-grained admin permissions on realm ${REALM_BETA}..."
retry 10 3 /opt/keycloak/bin/kcadm.sh update "realms/${REALM_BETA}" -s adminPermissionsEnabled=true

echo "Enabling IdP permissions for ${IDP_ALIAS}..."
retry 10 3 /opt/keycloak/bin/kcadm.sh update "identity-provider/instances/${IDP_ALIAS}/management/permissions" \
  -r "${REALM_BETA}" \
  -s enabled=true

lookup_client_id() {
  local client_id=$1
  /opt/keycloak/bin/kcadm.sh get clients -r "${REALM_BETA}" -q "clientId=${client_id}" --fields id --format csv --noquotes \
    | tail -n 1
}

wait_for_client() {
  local client_id=$1
  local id=""
  until id="$(lookup_client_id "${client_id}")" && [[ -n "${id}" && "${id}" != "id" ]]; do
    sleep 2
  done
  echo "${id}"
}

echo "Waiting for realm clients to be available..."
REALM_MGMT_ID="$(wait_for_client realm-management)"
BROKER_CLIENT_ID="$(wait_for_client "${BROKER_CLIENT}")"
INVENTORY_CLIENT_ID="$(wait_for_client inventory-api)"

echo "Waiting for realm-management authorization server..."
retry 15 3 /opt/keycloak/bin/kcadm.sh get "clients/${REALM_MGMT_ID}/authz/resource-server" -r "${REALM_BETA}" >/dev/null

IDP_PERMISSIONS=$(
  /opt/keycloak/bin/kcadm.sh get "identity-provider/instances/${IDP_ALIAS}/management/permissions" -r "${REALM_BETA}"
)
IDP_RESOURCE_ID=$(json_field "${IDP_PERMISSIONS}" resource)
IDP_TOKEN_EXCHANGE_PERMISSION_ID=$(echo "${IDP_PERMISSIONS}" | tr -d '\n' | sed -n 's/.*"token-exchange"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')

IDP_RESOURCE_JSON=$(
  /opt/keycloak/bin/kcadm.sh get "clients/${REALM_MGMT_ID}/authz/resource-server/resource/${IDP_RESOURCE_ID}" -r "${REALM_BETA}"
)
TOKEN_EXCHANGE_SCOPE_ID=""
scope_id=""
while IFS= read -r line; do
  if [[ "${line}" =~ \"id\"[[:space:]]*:[[:space:]]*\"([^\"]+)\" ]]; then
    scope_id="${BASH_REMATCH[1]}"
  fi
  if [[ "${line}" == *'"name" : "token-exchange"'* ]]; then
    TOKEN_EXCHANGE_SCOPE_ID="${scope_id}"
    break
  fi
done <<< "${IDP_RESOURCE_JSON}"

if [[ -z "${TOKEN_EXCHANGE_SCOPE_ID}" ]]; then
  echo "Unable to resolve token-exchange scope id" >&2
  exit 1
fi

lookup_policy_id() {
  /opt/keycloak/bin/kcadm.sh get "clients/${REALM_MGMT_ID}/authz/resource-server/policy" -r "${REALM_BETA}" \
    -q name=allow-order-service-broker --fields id --format csv --noquotes \
    | tail -n 1
}

ensure_client_policy() {
  POLICY_ID="$(lookup_policy_id)"
  if [[ -n "${POLICY_ID}" && "${POLICY_ID}" != "id" ]]; then
    return 0
  fi

  cat > /tmp/token-exchange-client-policy.json <<EOF
{
  "name": "allow-order-service-broker",
  "type": "client",
  "logic": "POSITIVE",
  "decisionStrategy": "UNANIMOUS",
  "config": {
    "clients": "[\"${BROKER_CLIENT_ID}\"]"
  }
}
EOF
  /opt/keycloak/bin/kcadm.sh create "clients/${REALM_MGMT_ID}/authz/resource-server/policy" \
    -r "${REALM_BETA}" \
    -f /tmp/token-exchange-client-policy.json >/dev/null

  POLICY_ID="$(lookup_policy_id)"
  [[ -n "${POLICY_ID}" && "${POLICY_ID}" != "id" ]]
}

echo "Ensuring client policy exists..."
retry 15 5 ensure_client_policy
echo "Using client policy ${POLICY_ID}"

IDP_PERMISSION_NAME=$(
  /opt/keycloak/bin/kcadm.sh get "clients/${REALM_MGMT_ID}/authz/resource-server/permission/scope/${IDP_TOKEN_EXCHANGE_PERMISSION_ID}" -r "${REALM_BETA}" \
    | sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1
)

cat > /tmp/idp-token-exchange-permission.json <<EOF
{
  "id": "${IDP_TOKEN_EXCHANGE_PERMISSION_ID}",
  "name": "${IDP_PERMISSION_NAME}",
  "type": "scope",
  "logic": "POSITIVE",
  "decisionStrategy": "UNANIMOUS",
  "resources": ["${IDP_RESOURCE_ID}"],
  "scopes": ["${TOKEN_EXCHANGE_SCOPE_ID}"],
  "policies": ["${POLICY_ID}"]
}
EOF

retry 10 3 /opt/keycloak/bin/kcadm.sh update \
  "clients/${REALM_MGMT_ID}/authz/resource-server/permission/scope/${IDP_TOKEN_EXCHANGE_PERMISSION_ID}" \
  -r "${REALM_BETA}" \
  -f /tmp/idp-token-exchange-permission.json

echo "Configured IdP token-exchange permission."

/opt/keycloak/bin/kcadm.sh update "clients/${INVENTORY_CLIENT_ID}/management/permissions" \
  -r "${REALM_BETA}" \
  -s enabled=true

INVENTORY_PERMISSIONS=$(
  /opt/keycloak/bin/kcadm.sh get "clients/${INVENTORY_CLIENT_ID}/management/permissions" -r "${REALM_BETA}"
)
INVENTORY_RESOURCE_ID=$(json_field "${INVENTORY_PERMISSIONS}" resource)
INVENTORY_TOKEN_EXCHANGE_PERMISSION_ID=$(echo "${INVENTORY_PERMISSIONS}" | tr -d '\n' | sed -n 's/.*"token-exchange"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')

INVENTORY_PERMISSION_NAME=$(
  /opt/keycloak/bin/kcadm.sh get "clients/${REALM_MGMT_ID}/authz/resource-server/permission/scope/${INVENTORY_TOKEN_EXCHANGE_PERMISSION_ID}" -r "${REALM_BETA}" \
    | sed -n 's/.*"name"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n 1
)

cat > /tmp/inventory-token-exchange-permission.json <<EOF
{
  "id": "${INVENTORY_TOKEN_EXCHANGE_PERMISSION_ID}",
  "name": "${INVENTORY_PERMISSION_NAME}",
  "type": "scope",
  "logic": "POSITIVE",
  "decisionStrategy": "UNANIMOUS",
  "resources": ["${INVENTORY_RESOURCE_ID}"],
  "scopes": ["${TOKEN_EXCHANGE_SCOPE_ID}"],
  "policies": ["${POLICY_ID}"]
}
EOF

retry 10 3 /opt/keycloak/bin/kcadm.sh update \
  "clients/${REALM_MGMT_ID}/authz/resource-server/permission/scope/${INVENTORY_TOKEN_EXCHANGE_PERMISSION_ID}" \
  -r "${REALM_BETA}" \
  -f /tmp/inventory-token-exchange-permission.json

echo "Configured inventory-api audience token-exchange permission."
echo "Token exchange setup complete."
