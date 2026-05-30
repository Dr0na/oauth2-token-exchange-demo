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
/opt/keycloak/bin/kcadm.sh update "realms/${REALM_BETA}" -s adminPermissionsEnabled=true

echo "Enabling IdP permissions for ${IDP_ALIAS}..."
/opt/keycloak/bin/kcadm.sh update "identity-provider/instances/${IDP_ALIAS}/management/permissions" \
  -r "${REALM_BETA}" \
  -s enabled=true

REALM_MGMT_ID=$(
  /opt/keycloak/bin/kcadm.sh get clients -r "${REALM_BETA}" -q clientId=realm-management --fields id --format csv --noquotes \
    | tail -n 1
)

BROKER_CLIENT_ID=$(
  /opt/keycloak/bin/kcadm.sh get clients -r "${REALM_BETA}" -q clientId="${BROKER_CLIENT}" --fields id --format csv --noquotes \
    | tail -n 1
)

INVENTORY_CLIENT_ID=$(
  /opt/keycloak/bin/kcadm.sh get clients -r "${REALM_BETA}" -q clientId=inventory-api --fields id --format csv --noquotes \
    | tail -n 1
)

if [[ -z "${BROKER_CLIENT_ID}" || "${BROKER_CLIENT_ID}" == "id" ]]; then
  echo "Unable to locate broker client ${BROKER_CLIENT}" >&2
  exit 1
fi

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

POLICY_ID=$(
  /opt/keycloak/bin/kcadm.sh get "clients/${REALM_MGMT_ID}/authz/resource-server/policy" -r "${REALM_BETA}" -q name=allow-order-service-broker --fields id --format csv --noquotes \
    | tail -n 1
)

if [[ -z "${POLICY_ID}" || "${POLICY_ID}" == "id" ]]; then
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
  POLICY_ID=$(
    /opt/keycloak/bin/kcadm.sh create "clients/${REALM_MGMT_ID}/authz/resource-server/policy" \
      -r "${REALM_BETA}" \
      -f /tmp/token-exchange-client-policy.json \
      -i
  )
  echo "Created client policy ${POLICY_ID}"
else
  echo "Reusing client policy ${POLICY_ID}"
fi

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

/opt/keycloak/bin/kcadm.sh update \
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

/opt/keycloak/bin/kcadm.sh update \
  "clients/${REALM_MGMT_ID}/authz/resource-server/permission/scope/${INVENTORY_TOKEN_EXCHANGE_PERMISSION_ID}" \
  -r "${REALM_BETA}" \
  -f /tmp/inventory-token-exchange-permission.json

echo "Configured inventory-api audience token-exchange permission."
echo "Token exchange setup complete."
