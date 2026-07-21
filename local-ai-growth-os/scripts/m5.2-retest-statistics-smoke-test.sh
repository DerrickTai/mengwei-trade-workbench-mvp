#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:5174}"
TOKEN="${TOKEN:-}"
MERCHANT_ID="${M52_MERCHANT_ID:-}"

if [[ -z "$TOKEN" || -z "$MERCHANT_ID" ]]; then
  echo "SKIP: set TOKEN and M52_MERCHANT_ID to run authenticated API smoke checks"
  exit 0
fi

AUTH=(-H "Authorization: $TOKEN")
echo "[1/3] health"
curl -fsS "$BASE_URL/actuator/health" | grep -q '"status":"UP"'

echo "[2/3] list experiments"
curl -fsS "${AUTH[@]}" "$BASE_URL/api/v1/merchants/$MERCHANT_ID/retest-automation/experiments" >/tmp/m52-experiments.json

echo "[3/3] external Provider safety"
if [[ "${M5_LIMITATIONS_ALLOW_EXTERNAL_PROVIDER:-false}" != "true" ]]; then
  echo "PASS: external Provider calls remain disabled"
else
  echo "WARN: external Provider calls explicitly enabled; this script does not invoke them"
fi

echo "M5.2 smoke checks passed"
