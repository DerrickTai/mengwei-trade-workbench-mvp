#!/usr/bin/env bash
# Local-only M3/M4 API smoke test. Credentials are supplied via environment.
set -euo pipefail
: "${M3_SMOKE_EMAIL:?set M3_SMOKE_EMAIL}"
: "${M3_SMOKE_PASSWORD:?set M3_SMOKE_PASSWORD}"
: "${M3_SMOKE_MERCHANT_ID:?set M3_SMOKE_MERCHANT_ID}"
: "${M3_SMOKE_DB_PASSWORD:?set M3_SMOKE_DB_PASSWORD for local cleanup}"
BASE_URL="${M3_SMOKE_BASE_URL:-http://localhost:5174/api/v1}"
DB_NAME="${M3_SMOKE_DB_NAME:-local_growth}"; DB_USER="${M3_SMOKE_DB_USER:-local_growth}"
MID="$M3_SMOKE_MERCHANT_ID"; QID=""; OID=""; ISSUE_ID=""; TASK_ID=""
need(){ command -v "$1" >/dev/null || { echo "missing command: $1" >&2; exit 1; }; }; need curl;need jq;need docker
cleanup(){ [[ -z "$QID" ]] && return 0; docker compose exec -T -e "PGPASSWORD=$M3_SMOKE_DB_PASSWORD" postgres psql -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB_NAME" -v qid="$QID" -v oid="$OID" -v issue="$ISSUE_ID" -v task="$TASK_ID" <<'SQL' >/dev/null
begin;
delete from ai_observation_fact_issues where id = nullif(:'issue','')::uuid;
delete from optimization_tasks where id = nullif(:'task','')::uuid;
delete from ai_observations where id = nullif(:'oid','')::uuid;
delete from consumer_questions where id = :'qid'::uuid;
commit;
SQL
echo "cleaned temporary M3 question $QID"; }
trap cleanup EXIT
login="$(curl -fsS -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' --data "$(jq -nc --arg email "$M3_SMOKE_EMAIL" --arg password "$M3_SMOKE_PASSWORD" '{email:$email,password:$password}')")"
TOKEN="$(jq -er '.accessToken' <<<"$login")"; auth=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json'); api(){ curl -fsS "${auth[@]}" "$@"; }
question="$(api -X POST "$BASE_URL/merchants/$MID/consumer-questions" --data '{"questionText":"M3 JDBC smoke：九江镇哪里适合做脸部清洁？","city":"佛山","district":"九江镇","industry":"美容","decisionStage":"CONSIDERATION","commercialValue":3,"targetPlatforms":["MEITUAN_WENXIAOTUAN"],"enabled":true,"demo":true,"notes":"M3 JDBC SMOKE TEMP"}')"; QID="$(jq -er '.id' <<<"$question")"
facts="$(api "$BASE_URL/merchants/$MID/facts")"; FACT_ID="$(jq -er '.[0].id' <<<"$facts")"
observation="$(api -X POST "$BASE_URL/merchants/$MID/ai-observations" --data "$(jq -nc --arg q "$QID" '{questionId:$q,aiPlatform:"MEITUAN_WENXIAOTUAN",observationMode:"MANUAL_APP",observedAt:"2026-07-17T10:00:00+08:00",locationText:"佛山九江镇",rawAnswer:"人工观察：未推荐千色坊，建议比较服务项目。",merchantMentioned:false,merchantRecommended:false,factCheckStatus:"ERROR",verificationStatus:"VERIFIED",mentionedCompetitors:[{name:"M3测试竞品",recommended:true,rank:1}],citedSources:[],demo:true,notes:"M3 JDBC SMOKE TEMP"}')")"; OID="$(jq -er '.id' <<<"$observation")"
issue="$(api -X POST "$BASE_URL/merchants/$MID/ai-observations/$OID/fact-issues" --data "$(jq -nc --arg fact "$FACT_ID" '{factId:$fact,issueType:"WRONG_VALUE",observedValue:"M3测试错误值",severity:"MEDIUM",resolved:false,resolutionNotes:"M3 JDBC SMOKE TEMP"}')")"; ISSUE_ID="$(jq -er '.id' <<<"$issue")"
dashboard="$(api "$BASE_URL/merchants/$MID/ai-observations/dashboard")"
task1="$(api -X POST "$BASE_URL/merchants/$MID/ai-observations/$OID/create-task" --data '{"reasonCode":"FACT_ERROR"}')"; TASK_ID="$(jq -er '.id' <<<"$task1")"
task2="$(api -X POST "$BASE_URL/merchants/$MID/ai-observations/$OID/create-task" --data '{"reasonCode":"FACT_ERROR"}')"; [[ "$(jq -er '.id' <<<"$task2")" == "$TASK_ID" ]] || { echo 'task idempotency failed' >&2; exit 1; }
jq -n --arg questionId "$QID" --arg observationId "$OID" --arg issueId "$ISSUE_ID" --arg taskId "$TASK_ID" --argjson dashboard "$dashboard" '{status:"OK",questionId:$questionId,observationId:$observationId,issueId:$issueId,taskId:$taskId,dashboard:$dashboard}'
