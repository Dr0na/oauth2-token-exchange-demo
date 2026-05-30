#!/usr/bin/env bash
set -euo pipefail

AUTO="${DEMO_AUTO:-false}"
PAUSE="${DEMO_PAUSE:-true}"

ALPHA_URL="${KEYCLOAK_ALPHA_URL:-http://localhost:8180}"
ORDER_URL="${ORDER_SERVICE_URL:-http://localhost:8080}"
REALM="${KEYCLOAK_ALPHA_REALM:-customer}"
CLIENT_ID="${ORDER_PORTAL_CLIENT_ID:-order-portal}"
USERNAME="${DEMO_USERNAME:-alice}"
PASSWORD="${DEMO_PASSWORD:-alice}"
ORDER_ID="${DEMO_ORDER_ID:-ORD-1001}"

step() {
  echo
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo " STEP $1: $2"
  echo " Actor: $3"
  echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
  echo "$4"
  echo
  if [[ "${PAUSE}" == "true" && "${AUTO}" != "true" ]]; then
    read -r -p "Press Enter for next step… "
  elif [[ "${AUTO}" == "true" ]]; then
    sleep 1
  fi
}

decode_jwt() {
  python3 - <<'PY' "$1"
import base64, json, sys
token = sys.argv[1]
try:
    payload = token.split('.')[1]
    padded = payload + '=' * (-len(payload) % 4)
    print(json.dumps(json.loads(base64.urlsafe_b64decode(padded)), indent=2))
except Exception as exc:
    print(json.dumps({"error": str(exc)}))
PY
}

preview_token() {
  local token=$1
  echo "${token:0:12}…${token: -8}"
}

# ── Step 1 ────────────────────────────────────────────────────────────────────
step 1 "Obtain customer access token" "Browser/CLI → Keycloak Alpha (realm: customer)" \
  "POST ${ALPHA_URL}/realms/${REALM}/protocol/openid-connect/token
Body: grant_type=password&client_id=${CLIENT_ID}&scope=openid profile email&username=${USERNAME}&password=***"

TOKEN_RESPONSE=$(
  curl -sS -X POST "${ALPHA_URL}/realms/${REALM}/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password" \
    -d "client_id=${CLIENT_ID}" \
    -d "scope=openid profile email" \
    -d "username=${USERNAME}" \
    -d "password=${PASSWORD}"
)

ACCESS_TOKEN=$(echo "${TOKEN_RESPONSE}" | python3 -c "import json,sys; print(json.load(sys.stdin).get('access_token',''))")

if [[ -z "${ACCESS_TOKEN}" ]]; then
  echo "Step 1 failed:" >&2
  echo "${TOKEN_RESPONSE}" >&2
  exit 1
fi

echo "Token response (sanitized):"
echo "${TOKEN_RESPONSE}" | python3 -c "import json,sys; d=json.load(sys.stdin); d['access_token']='***'; d['refresh_token']='***'; print(json.dumps(d, indent=2))"
echo
echo "Decoded customer JWT claims:"
decode_jwt "${ACCESS_TOKEN}"

# ── Step 2 ────────────────────────────────────────────────────────────────────
step 2 "Call order service walkthrough" "CLI → Order Service (:8080)" \
  "GET ${ORDER_URL}/api/demo/walkthrough/${ORDER_ID}
Header: Authorization: Bearer $(preview_token "${ACCESS_TOKEN}")"

WALKTHROUGH=$(
  curl -sS "${ORDER_URL}/api/demo/walkthrough/${ORDER_ID}" \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H "Accept: application/json"
)

if ! echo "${WALKTHROUGH}" | python3 -c "import json,sys; json.load(sys.stdin)" >/dev/null 2>&1; then
  echo "Step 2 failed:" >&2
  echo "${WALKTHROUGH}" >&2
  exit 1
fi

echo "Walkthrough accepted. Server-side trace follows…"

print_server_step() {
  local num=$1
  WALKTHROUGH_JSON="${WALKTHROUGH}" STEP_NUM="${num}" python3 - <<'PY'
import json, os
data = json.loads(os.environ["WALKTHROUGH_JSON"])
num = int(os.environ["STEP_NUM"])
steps = {s["step"]: s for s in data.get("steps", [])}
s = steps.get(num)
if not s:
    raise SystemExit(0)
print(f"Description: {s.get('description','')}")
print()
req = s.get("request") or {}
print("Request:")
print(f"  {req.get('method','?')} {req.get('url','?')}")
for k, v in (req.get("headers") or {}).items():
    print(f"  {k}: {v}")
if req.get("body"):
    print(req["body"])
print()
res = s.get("response") or {}
print("Response:")
print(f"  {res.get('method','?')} {res.get('url','?')}")
if res.get("body"):
    print(res["body"])
print()
if s.get("decodedJwt"):
    print("Decoded JWT / claims:")
    print(json.dumps(s["decodedJwt"], indent=2))
    print()
if s.get("notes"):
    print(f"Notes: {s['notes']}")
if s.get("durationMs"):
    print(f"Duration: {s['durationMs']} ms")
PY
}

# ── Steps 3–6 (server-side trace) ─────────────────────────────────────────────
for n in 3 4 5 6; do
  TITLE=$(echo "${WALKTHROUGH}" | python3 -c "import json,sys; d=json.load(sys.stdin); print(next(s['title'] for s in d['steps'] if s['step']==${n}))")
  ACTOR=$(echo "${WALKTHROUGH}" | python3 -c "import json,sys; d=json.load(sys.stdin); print(next(s['actor'] for s in d['steps'] if s['step']==${n}))")
  step "${n}" "${TITLE}" "${ACTOR}" "(executed inside order service — trace returned in walkthrough response)"
  print_server_step "${n}"
done

echo
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " FINAL RESULT"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "${WALKTHROUGH}" | python3 -c "import json,sys; print(json.dumps(json.load(sys.stdin)['result'], indent=2))"
