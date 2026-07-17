#!/usr/bin/env bash
set -euo pipefail

# Local-only M5 smoke test. Credentials and the target merchant are deliberately
# supplied by the caller; this script never stores application secrets.
: "${M5_SMOKE_EMAIL:?set M5_SMOKE_EMAIL}"
: "${M5_SMOKE_PASSWORD:?set M5_SMOKE_PASSWORD}"
: "${M5_SMOKE_MERCHANT_ID:?set M5_SMOKE_MERCHANT_ID}"

BASE_URL="${M5_SMOKE_BASE_URL:-http://localhost:5174/api}"
COMPOSE_DIR="${M5_SMOKE_COMPOSE_DIR:-$(cd "$(dirname "$0")/.." && pwd)}"
OBSERVATION_ID=""
SNAPSHOT_ID=""

api() {
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -fsS -X "$method" "$BASE_URL$path" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data "$body"
  else
    curl -fsS -X "$method" "$BASE_URL$path" -H "Authorization: Bearer $TOKEN"
  fi
}

cleanup() {
  [[ -z "$OBSERVATION_ID$SNAPSHOT_ID" ]] && return 0
  local psql=(docker compose exec -T postgres psql -U "${M5_SMOKE_DB_USER:-local_growth}" -d "${M5_SMOKE_DB_NAME:-local_growth}" -v ON_ERROR_STOP=1)
  local run_id
  run_id=$(cd "$COMPOSE_DIR" && "${psql[@]}" -tAc "select task_diagnostic_run_id from geo_diagnosis_snapshots where id='$SNAPSHOT_ID'" | tr -d '[:space:]')
  (cd "$COMPOSE_DIR" && "${psql[@]}" -c "delete from geo_strategy_task_links where snapshot_id='$SNAPSHOT_ID'; delete from optimization_tasks where diagnostic_run_id='$run_id'; delete from geo_diagnosis_snapshots where id='$SNAPSHOT_ID'; delete from diagnostic_runs where id='$run_id'; delete from ai_observation_fact_issues where observation_id='$OBSERVATION_ID'; delete from ai_observations where id='$OBSERVATION_ID';") >/dev/null
}
trap cleanup EXIT

LOGIN=$(curl -fsS -X POST "$BASE_URL/v1/auth/login" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg email "$M5_SMOKE_EMAIL" --arg password "$M5_SMOKE_PASSWORD" '{email:$email,password:$password}')")
TOKEN=$(jq -er '.accessToken' <<<"$LOGIN")
CONFIG=$(api GET "/v1/merchants/$M5_SMOKE_MERCHANT_ID/geo-strategy-config")
QUESTION_ID=$(jq -er '.questionIds[0]' <<<"$CONFIG")

# This is non-demo and VERIFIED only for the duration of this smoke test. It is
# deleted by the trap, so formal client evidence is never polluted.
OBSERVATION=$(api POST "/v1/merchants/$M5_SMOKE_MERCHANT_ID/ai-observations" "$(jq -nc \
  --arg questionId "$QUESTION_ID" --arg observedAt "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  '{questionId:$questionId,aiPlatform:"DEEPSEEK",observationMode:"MANUAL_APP",observedAt:$observedAt,rawAnswer:"M5 smoke: ordinary platform answer without a merchant recommendation.",merchantMentioned:false,merchantRecommended:false,factCheckStatus:"CORRECT",citedSources:[{sourceType:"XIAOHONGSHU",sourceName:"Smoke source",url:"https://www.xiaohongshu.com/explore/m5-smoke"},{sourceType:"MANUAL",sourceName:"Unknown source",url:"not-a-valid-url"}],mentionedCompetitors:[{name:"M5 Smoke Competitor"}],verificationStatus:"VERIFIED",demo:false,notes:"M5 SMOKE TEMP - auto cleanup"}')")
OBSERVATION_ID=$(jq -er '.id' <<<"$OBSERVATION")

SNAPSHOT=$(api POST "/v1/merchants/$M5_SMOKE_MERCHANT_ID/geo-diagnoses" '{}')
SNAPSHOT_ID=$(jq -er '.id' <<<"$SNAPSHOT")
jq -e '.ruleVersion == "GEO_STRATEGY_V1" and .observationCount >= 1 and .metrics.totalObservations >= 1' <<<"$SNAPSHOT" >/dev/null

TASKS_ONE=$(api POST "/v1/merchants/$M5_SMOKE_MERCHANT_ID/geo-diagnoses/$SNAPSHOT_ID/optimization-tasks" '{}')
TASKS_TWO=$(api POST "/v1/merchants/$M5_SMOKE_MERCHANT_ID/geo-diagnoses/$SNAPSHOT_ID/optimization-tasks" '{}')
[[ "$(jq -S '[.[].id] | sort' <<<"$TASKS_ONE")" == "$(jq -S '[.[].id] | sort' <<<"$TASKS_TWO")" ]]

echo "M5 smoke test passed: immutable snapshot=$SNAPSHOT_ID, verified observations=$(jq -r '.observationCount' <<<"$SNAPSHOT"), task creation is idempotent."
