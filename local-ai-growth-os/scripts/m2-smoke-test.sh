#!/usr/bin/env bash
# Local M2 write-path smoke test. It uses HTTP APIs for business operations;
# direct SQL only removes this script's exact temporary records.
set -euo pipefail

: "${M2_SMOKE_EMAIL:?set M2_SMOKE_EMAIL}"
: "${M2_SMOKE_PASSWORD:?set M2_SMOKE_PASSWORD}"
: "${M2_SMOKE_MERCHANT_ID:?set M2_SMOKE_MERCHANT_ID}"
: "${M2_SMOKE_DB_PASSWORD:?set M2_SMOKE_DB_PASSWORD for local cleanup}"

BASE_URL="${M2_SMOKE_BASE_URL:-http://localhost:5174/api/v1}"
DB_NAME="${M2_SMOKE_DB_NAME:-local_growth}"
DB_USER="${M2_SMOKE_DB_USER:-local_growth}"
MERCHANT_ID="$M2_SMOKE_MERCHANT_ID"
PROFILE_ID=""
TASK_ID=""

require() { command -v "$1" >/dev/null || { echo "missing required command: $1" >&2; exit 1; }; }
require curl
require jq
require docker

cleanup() {
  [[ -z "$PROFILE_ID" ]] && return 0
  docker compose exec -T -e "PGPASSWORD=$M2_SMOKE_DB_PASSWORD" postgres \
    psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" \
    -v profile_id="$PROFILE_ID" -v task_id="$TASK_ID" <<'SQL' >/dev/null
begin;
update platform_asset_gaps set generated_task_id = null where generated_task_id = nullif(:'task_id', '')::uuid;
delete from optimization_tasks where id = nullif(:'task_id', '')::uuid;
delete from platform_asset_gaps where platform_profile_id = :'profile_id'::uuid;
delete from platform_profile_fields where profile_id = :'profile_id'::uuid;
delete from platform_profiles where id = :'profile_id'::uuid;
commit;
SQL
  echo "cleaned temporary profile $PROFILE_ID"
}
trap cleanup EXIT

login_json="$(curl -fsS -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg email "$M2_SMOKE_EMAIL" --arg password "$M2_SMOKE_PASSWORD" '{email:$email,password:$password}')")"
TOKEN="$(jq -er '.accessToken' <<<"$login_json")"
auth=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json')
api() { curl -fsS "${auth[@]}" "$@"; }

profile_json="$(api -X POST "$BASE_URL/merchants/$MERCHANT_ID/platform-assets" \
  --data '{"platform":"XIAOHONGSHU","profileScope":"BRAND","accountName":"M2 JDBC smoke","notes":"M2 JDBC SMOKE TEMP"}')"
PROFILE_ID="$(jq -er '.id' <<<"$profile_json")"

field_v1="$(api -X POST "$BASE_URL/merchants/$MERCHANT_ID/platform-assets/$PROFILE_ID/fields" \
  --data '{"fieldKey":"BRAND_NAME","value":"M2 JDBC Smoke V1","status":"VERIFIED","sourceType":"MANUAL_ENTRY"}')"
FIELD_V1_ID="$(jq -er '.id' <<<"$field_v1")"

field_v2="$(api -X POST "$BASE_URL/merchants/$MERCHANT_ID/platform-assets/$PROFILE_ID/fields" \
  --data '{"fieldKey":"BRAND_NAME","value":"M2 JDBC Smoke V2","status":"VERIFIED","sourceType":"MANUAL_ENTRY"}')"
FIELD_V2_ID="$(jq -er '.id' <<<"$field_v2")"

history="$(api "$BASE_URL/merchants/$MERCHANT_ID/platform-assets/$PROFILE_ID/fields")"
count="$(jq --arg v1 "$FIELD_V1_ID" --arg v2 "$FIELD_V2_ID" '[.[] | select(.id == $v1 or .id == $v2)] | length' <<<"$history")"
[[ "$count" == "2" ]] || { echo "field history did not contain both versions" >&2; exit 1; }

comparison="$(api -X POST "$BASE_URL/merchants/$MERCHANT_ID/platform-assets/$PROFILE_ID/compare")"
gaps="$(api "$BASE_URL/merchants/$MERCHANT_ID/platform-assets/gaps")"
GAP_ID="$(jq -r --arg profile "$PROFILE_ID" '[.[] | select(.platform_profile_id == $profile and .status == "OPEN")][0].id // empty' <<<"$gaps")"
if [[ -n "$GAP_ID" ]]; then
  task="$(api -X POST "$BASE_URL/merchants/$MERCHANT_ID/platform-asset-gaps/$GAP_ID/create-task")"
  TASK_ID="$(jq -er '.id' <<<"$task")"
  api -X PATCH "$BASE_URL/merchants/$MERCHANT_ID/platform-assets/gaps/$GAP_ID" --data '{"status":"IGNORED"}' >/dev/null
fi

jq -n --arg profileId "$PROFILE_ID" --arg fieldV1Id "$FIELD_V1_ID" --arg fieldV2Id "$FIELD_V2_ID" \
  --arg taskId "$TASK_ID" --argjson comparison "$comparison" \
  '{status:"OK",profileId:$profileId,fieldV1Id:$fieldV1Id,fieldV2Id:$fieldV2Id,taskId:$taskId,comparison:$comparison}'
