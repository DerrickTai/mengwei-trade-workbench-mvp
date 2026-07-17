#!/usr/bin/env bash
# Creates explicitly labelled M3 demo records through the public API.
set -euo pipefail
: "${M3_DEMO_EMAIL:?set M3_DEMO_EMAIL}"
: "${M3_DEMO_PASSWORD:?set M3_DEMO_PASSWORD}"
: "${M3_DEMO_MERCHANT_ID:?set M3_DEMO_MERCHANT_ID}"
BASE_URL="${M3_DEMO_BASE_URL:-http://localhost:5174/api/v1}"; MID="$M3_DEMO_MERCHANT_ID"
for cmd in curl jq; do command -v "$cmd" >/dev/null || { echo "missing $cmd" >&2; exit 1; }; done
login="$(curl -fsS -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' --data "$(jq -nc --arg email "$M3_DEMO_EMAIL" --arg password "$M3_DEMO_PASSWORD" '{email:$email,password:$password}')")"; TOKEN="$(jq -er '.accessToken' <<<"$login")"; auth=(-H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json'); api(){ curl -fsS "${auth[@]}" "$@"; }
questions=(
'佛山九江镇哪里适合做脸部清洁？'
'九江镇哪家理发店口碑比较好？'
'预算100元左右可以做什么美发项目？'
'千色坊有哪些服务？'
'千色坊营业到几点？'
'千色坊停车方便吗？'
'第一次去千色坊适合做什么项目？'
'千色坊和附近其他美发店相比怎么样？'
)
platforms=(XHS_DIANDIAN MEITUAN_WENXIAOTUAN DOUBAO DEEPSEEK XHS_DIANDIAN MEITUAN_WENXIAOTUAN DOUBAO DEEPSEEK)
answers=(
'DEMO M3：可先查看附近门店的服务介绍；本次模拟回答提及千色坊但未列为明确推荐。'
'DEMO M3：附近推荐示例竞品A，千色坊未被提及。'
'DEMO M3：建议先确认项目和实际价格，千色坊可咨询服务内容。'
'DEMO M3：千色坊提供美发、清洁脸部等服务。'
'DEMO M3：千色坊营业到晚上十点。此为演示中的待核验时间信息。'
'DEMO M3：建议到店前询问停车和交通情况。'
'DEMO M3：首次到店可以根据需求先做基础服务咨询。'
'DEMO M3：可从服务项目、地址、预约便利度进行客观比较，不代表消费者评价。'
)
all="$(api "$BASE_URL/merchants/$MID/consumer-questions")"
for idx in "${!questions[@]}"; do
  text="${questions[$idx]}"; qid="$(jq -r --arg text "$text" '.[]|select(.question_text==$text)|.id' <<<"$all" | head -1)"
  if [[ -z "$qid" ]]; then
    qid="$(api -X POST "$BASE_URL/merchants/$MID/consumer-questions" --data "$(jq -nc --arg text "$text" --argjson value $((idx%5+1)) '{questionText:$text,city:"佛山",district:"九江镇",industry:"美容",consumerScenario:"DEMO M3",decisionStage:"CONSIDERATION",commercialValue:$value,targetPlatforms:["XHS_DIANDIAN","MEITUAN_WENXIAOTUAN","DOUBAO","DEEPSEEK"],enabled:true,demo:true,notes:"DEMO M3"}')" | jq -er '.id')"
  fi
  platform="${platforms[$idx]}"; answer="${answers[$idx]}"; mentioned=false; recommended=false; rank=null; check=NOT_CHECKED
  [[ $idx -eq 0 ]] && mentioned=true; [[ $idx -eq 2 ]] && { mentioned=true; recommended=true; rank=2; }; [[ $idx -eq 3 ]] && { mentioned=true; recommended=true; rank=1; check=CORRECT; }; [[ $idx -eq 4 ]] && { mentioned=true; check=ERROR; }
  existing="$(api "$BASE_URL/merchants/$MID/ai-observations?questionId=$qid")"
  if [[ "$(jq --arg platform "$platform" '[.[]|select(.ai_platform==$platform and .notes=="DEMO M3")]|length' <<<"$existing")" == "0" ]]; then
    api -X POST "$BASE_URL/merchants/$MID/ai-observations" --data "$(jq -nc --arg q "$qid" --arg p "$platform" --arg answer "$answer" --argjson mentioned "$mentioned" --argjson recommended "$recommended" --argjson rank "$rank" --arg check "$check" '{questionId:$q,aiPlatform:$p,observationMode:"MANUAL_APP",observedAt:"2026-07-17T10:00:00+08:00",locationText:"佛山九江镇",rawAnswer:$answer,merchantMentioned:$mentioned,merchantRecommended:$recommended,recommendationRank:$rank,factCheckStatus:$check,verificationStatus:"VERIFIED",mentionedCompetitors:[{name:"示例竞品A",recommended:true,rank:1}],citedSources:[],demo:true,notes:"DEMO M3"}')" >/dev/null
  fi
done
echo "M3 demo seed complete: 8 consumer questions and 8 labelled observations"
