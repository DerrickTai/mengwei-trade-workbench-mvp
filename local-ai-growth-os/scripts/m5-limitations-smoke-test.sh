#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:5174/api/v1}"
TOKEN="${TOKEN:?TOKEN is required}"
MERCHANT_ID="${MERCHANT_ID:?MERCHANT_ID is required}"
QUESTION_ID="${QUESTION_ID:?QUESTION_ID is required}"
ALLOW_EXTERNAL_PROVIDER="${M5_LIMITATIONS_ALLOW_EXTERNAL_PROVIDER:-false}"

if [[ "$ALLOW_EXTERNAL_PROVIDER" != "true" ]]; then
  echo "Refusing to call an external model provider. Set M5_LIMITATIONS_ALLOW_EXTERNAL_PROVIDER=true to opt in."
  exit 2
fi

auth=(-H "Authorization: Bearer ${TOKEN}" -H "Content-Type: application/json")

echo "[1/7] create DeepSeek collector config"
CONFIG_JSON="$(
  curl -fsS "${auth[@]}" \
    -X POST "${BASE_URL}/merchants/${MERCHANT_ID}/observation-automation/collector-configs" \
    -d '{
      "name":"smoke-deepseek-official",
      "aiPlatform":"DEEPSEEK",
      "collectionChannel":"OFFICIAL_API",
      "providerCode":"DEEPSEEK_OFFICIAL",
      "apiBaseUrl":"https://api.deepseek.com/v1",
      "modelName":"deepseek-chat",
      "secretEnvName":"GEO_DEEPSEEK_API_KEY",
      "webSearchEnabled":false,
      "requestOptions":{"maxAttempts":2,"timeoutSeconds":30,"maxTokens":512},
      "autoCreateDraft":true,
      "enabled":true
    }'
)"
CONFIG_ID="$(printf '%s' "$CONFIG_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"

echo "[2/7] run collection"
RUN_JSON="$(
  curl -fsS "${auth[@]}" \
    -X POST "${BASE_URL}/merchants/${MERCHANT_ID}/observation-automation/runs" \
    -d "{
      \"collectorConfigIds\":[\"${CONFIG_ID}\"],
      \"questionIds\":[\"${QUESTION_ID}\"],
      \"autoCreateDraft\":true
    }"
)"
RUN_ID="$(printf '%s' "$RUN_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"

echo "[3/7] inspect results"
curl -fsS "${auth[@]}" \
  "${BASE_URL}/merchants/${MERCHANT_ID}/observation-automation/runs/${RUN_ID}/results"

echo
echo "[4/7] verify no secret returned"
if printf '%s' "$CONFIG_JSON$RUN_JSON" | grep -qE 'sk-[A-Za-z0-9]'; then
  echo "FAIL: response appears to contain an API key"
  exit 1
fi

echo "[5/7] register publication"
curl -fsS "${auth[@]}" \
  -X POST "${BASE_URL}/merchants/${MERCHANT_ID}/tracked-publications" \
  -d '{
    "platform":"OFFICIAL_WEBSITE",
    "title":"Smoke Test Publication",
    "url":"https://example.com/smoke-test?utm_source=ignored",
    "status":"ACTIVE"
  }'

echo
echo "[6/7] scan citations"
curl -fsS "${auth[@]}" \
  -X POST "${BASE_URL}/merchants/${MERCHANT_ID}/tracked-publications/scan-citations" \
  -d '{}'

echo
echo "[7/7] list source evidence"
curl -fsS "${auth[@]}" \
  "${BASE_URL}/merchants/${MERCHANT_ID}/source-evidence"

echo
echo "M5 limitation remediation smoke test completed."
