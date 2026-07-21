<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useMerchantContext } from '../../stores/merchantContext'
import * as m6 from './api'

const ctx = useMerchantContext()
const loading = ref(false)
const error = ref('')
const plans = ref<any[]>([])
const items = ref<any[]>([])
const history = ref<any[]>([])
const selectedPlan = ref<any>()
const selectedWork = ref<any>()
const selectedBrief = ref<any>()
const draftHistory = ref<any[]>([])
const selectedDraft = ref<any>()
const draftLoading = ref(false)
const generating = ref(false)
const reviewing = ref(false)
const generationError = ref('')
const pendingGenerationKey = ref('')
const reviewComment = ref('')

const planForm = ref<any>({ diagnosisSnapshotId:'', idempotencyKey:crypto.randomUUID(), name:'30天 GEO 内容执行计划', periodStart:new Date().toISOString().slice(0,10), periodEnd:new Date(Date.now()+29*86400000).toISOString().slice(0,10), status:'DRAFT' })
const workForm = ref<any>({ taskType:'FAQ_CONTENT', title:'', businessValue:3, gapSeverity:3, executionFeasibility:3, evidenceConfidence:3, status:'PLANNED', targetQuestionIds:[], successCriteria:{} })
const briefForm = ref<any>({ status:'BRIEF_DRAFT', contentGoal:'', targetPlatform:'WEBSITE', contentType:'FAQ', targetQuestionIds:[], requiredFactIds:[], prohibitedClaims:[], contentStructure:[], successCriteria:{}, retestCriteria:{} })

function getError(error:any) { return error?.message || '请求失败，请稍后重试' }
function normalizeJson<T>(value:any, fallback:T):T {
  if (value === null || value === undefined || value === '') return fallback
  if (typeof value === 'object') return value as T
  if (typeof value === 'string') {
    try { return JSON.parse(value) as T } catch { return fallback }
  }
  return fallback
}
function displayStatus(value:string) { return value || '—' }
function draftTagType(status:string) {
  return ({ DRAFT:'info', REVIEW_PENDING:'warning', APPROVED:'success', REJECTED:'danger', EVIDENCE_BLOCKED:'danger', RISK_BLOCKED:'danger', FAILED:'danger', SUPERSEDED:'info' } as Record<string, any>)[status] || 'info'
}
const structured = computed<any>(() => normalizeJson(selectedDraft.value?.structured_content, {}))
const evidencePack = computed<any[]>(() => normalizeJson(selectedDraft.value?.evidence_pack, []))
const claimResult = computed<any>(() => normalizeJson(selectedDraft.value?.claim_evidence_result, {}))
const riskResult = computed<any>(() => normalizeJson(selectedDraft.value?.risk_scan_result, {}))
const metadata = computed<any>(() => normalizeJson(selectedDraft.value?.generation_metadata, {}))
const sections = computed<any[]>(() => Array.isArray(structured.value?.sections) ? structured.value.sections : [])
const faqItems = computed<any[]>(() => Array.isArray(structured.value?.faqItems) ? structured.value.faqItems : [])
const riskFindings = computed<any[]>(() => Array.isArray(riskResult.value?.findings) ? riskResult.value.findings : [])
const blocked = computed(() => ['EVIDENCE_BLOCKED', 'RISK_BLOCKED'].includes(selectedDraft.value?.status))
const canSubmit = computed(() => selectedDraft.value?.status === 'DRAFT')
const canDecide = computed(() => selectedDraft.value?.status === 'REVIEW_PENDING')

async function load() {
  if (!ctx.merchant.value) return
  loading.value = true
  error.value = ''
  try { plans.value = await m6.plans(ctx.merchant.value.id) } catch (exception:any) { error.value = getError(exception) } finally { loading.value = false }
}
async function openPlan(plan:any) {
  selectedPlan.value = plan
  selectedWork.value = undefined
  selectedBrief.value = undefined
  selectedDraft.value = undefined
  draftHistory.value = []
  try { items.value = await m6.works(ctx.merchant.value!.id, plan.id) } catch (exception:any) { ElMessage.error(getError(exception)) }
}
async function savePlan() {
  try {
    const plan = selectedPlan.value ? await m6.updatePlan(ctx.merchant.value!.id, selectedPlan.value.id, planForm.value) : await m6.createPlan(ctx.merchant.value!.id, planForm.value)
    await load(); await openPlan(plan); ElMessage.success('计划已保存')
  } catch (exception:any) { ElMessage.error(getError(exception)) }
}
async function saveWork() {
  try {
    if (!selectedPlan.value) return
    const work = selectedWork.value ? await m6.updateWork(ctx.merchant.value!.id, selectedWork.value.id, workForm.value) : await m6.createWork(ctx.merchant.value!.id, selectedPlan.value.id, workForm.value)
    await openPlan(selectedPlan.value); selectedWork.value = work; ElMessage.success('工作项已保存')
  } catch (exception:any) { ElMessage.error(getError(exception)) }
}
async function openWork(work:any) {
  selectedWork.value = work
  selectedBrief.value = undefined
  selectedDraft.value = undefined
  draftHistory.value = []
  try {
    history.value = await m6.briefs(ctx.merchant.value!.id, work.id)
    if (history.value.length) await selectBrief(history.value[0])
  } catch (exception:any) { ElMessage.error(getError(exception)) }
}
async function saveBrief() {
  try {
    if (!selectedWork.value) return
    const brief = history.value.length ? await m6.updateBrief(ctx.merchant.value!.id, history.value[0].id, briefForm.value) : await m6.createBrief(ctx.merchant.value!.id, selectedWork.value.id, briefForm.value)
    history.value = await m6.briefs(ctx.merchant.value!.id, selectedWork.value.id)
    await selectBrief(brief)
    ElMessage.success(`Brief v${brief.version} 已保存`)
  } catch (exception:any) { ElMessage.error(getError(exception)) }
}
async function selectBrief(brief:any) {
  selectedBrief.value = brief
  selectedDraft.value = undefined
  generationError.value = ''
  pendingGenerationKey.value = ''
  await loadDrafts()
}
async function loadDrafts() {
  if (!ctx.merchant.value || !selectedBrief.value) return
  draftLoading.value = true
  try {
    draftHistory.value = await m6.drafts(ctx.merchant.value.id, selectedBrief.value.id)
    if (draftHistory.value.length) await openDraft(draftHistory.value[0])
  } catch (exception:any) { ElMessage.error(getError(exception)) } finally { draftLoading.value = false }
}
async function openDraft(draft:any) {
  if (!ctx.merchant.value) return
  draftLoading.value = true
  try { selectedDraft.value = await m6.draft(ctx.merchant.value.id, draft.id); reviewComment.value = selectedDraft.value.review_comment || '' } catch (exception:any) { ElMessage.error(getError(exception)) } finally { draftLoading.value = false }
}
async function generateDraft(retry=false) {
  if (!ctx.merchant.value || !selectedBrief.value || generating.value) return
  generationError.value = ''
  if (!retry || !pendingGenerationKey.value) pendingGenerationKey.value = crypto.randomUUID()
  generating.value = true
  try {
    const draft = await m6.generateDraft(ctx.merchant.value.id, selectedBrief.value.id, pendingGenerationKey.value)
    pendingGenerationKey.value = ''
    await loadDrafts()
    await openDraft(draft)
    ElMessage.success('AI 草稿已生成，等待人工审核')
  } catch (exception:any) {
    generationError.value = getError(exception)
    ElMessage.error(generationError.value)
  } finally { generating.value = false }
}
async function review(action:'SUBMIT'|'APPROVE'|'REJECT') {
  if (!ctx.merchant.value || !selectedDraft.value || reviewing.value) return
  reviewing.value = true
  try {
    const updated = await m6.reviewDraft(ctx.merchant.value.id, selectedDraft.value.id, action, reviewComment.value)
    selectedDraft.value = updated
    await loadDrafts()
    await openDraft(updated)
    ElMessage.success(action === 'SUBMIT' ? '已提交人工审核' : action === 'APPROVE' ? '草稿已通过审核' : '草稿已驳回')
  } catch (exception:any) { ElMessage.error(getError(exception)) } finally { reviewing.value = false }
}

watch(() => ctx.merchant.value?.id, load, { immediate:true })
</script>

<template>
  <section class="module-page content-execution">
    <div class="module-heading"><div><span class="eyebrow">M6 · CONTENT EXECUTION</span><h2>内容执行中心</h2><p>诊断 → 30天计划 → 工作项 → 可版本化 Content Brief → 人工审核草稿。</p></div></div>
    <el-alert type="info" :closable="false" title="内容由 AI 辅助生成，必须经过人工审核，系统不会自动发布。"/>
    <el-alert v-if="error" type="error" :closable="false" :title="error" style="margin-top:12px"/>

    <div class="module-columns" style="margin-top:18px">
      <article class="panel"><h3>执行计划</h3><el-button :loading="loading" @click="load">刷新</el-button><el-empty v-if="!plans.length&&!loading" description="暂无执行计划"/>
        <div v-for="plan in plans" :key="plan.id" class="data-row"><b>{{plan.name}}</b><small>{{plan.period_start}} ～ {{plan.period_end}} · {{plan.status}}</small><el-button text @click="openPlan(plan)">打开</el-button></div>
        <h4>创建 / 编辑计划</h4><el-input v-model="planForm.diagnosisSnapshotId" placeholder="正式诊断 Snapshot ID"/><el-input v-model="planForm.name" placeholder="计划名称"/><el-date-picker v-model="planForm.periodStart" value-format="YYYY-MM-DD" type="date"/><el-date-picker v-model="planForm.periodEnd" value-format="YYYY-MM-DD" type="date"/><el-select v-model="planForm.status"><el-option v-for="status in ['DRAFT','ACTIVE','COMPLETED','CANCELLED']" :key="status" :value="status"/></el-select><el-button type="primary" @click="savePlan">保存计划</el-button>
      </article>

      <article class="panel"><h3>工作项与 Brief</h3><el-empty v-if="!selectedPlan" description="先打开一个计划"/>
        <template v-else><div v-for="work in items" :key="work.id" class="data-row"><b>{{work.title}}</b><small>{{work.status}} · {{work.priority_level}} · {{work.due_date||'未设截止日期'}}</small><el-button text @click="openWork(work)">打开工作项</el-button></div>
          <el-input v-model="workForm.title" placeholder="工作项标题"/><el-select v-model="workForm.status"><el-option v-for="status in ['PLANNED','IN_PROGRESS','REVIEW_PENDING','APPROVED','COMPLETED','BLOCKED','CANCELLED','SUPERSEDED']" :key="status" :value="status"/></el-select><el-button @click="saveWork">保存工作项</el-button>
          <template v-if="selectedWork"><h4>Content Brief</h4><el-input v-model="briefForm.contentGoal" placeholder="内容目标"/><el-select v-model="briefForm.targetPlatform"><el-option value="WEBSITE"/><el-option value="ZHIHU"/><el-option value="XIAOHONGSHU"/></el-select><el-input v-model="briefForm.contentType" placeholder="内容类型"/><el-button type="primary" @click="saveBrief">创建新 Brief 版本</el-button>
            <div v-if="!history.length" class="empty-inline">当前工作项尚无 Brief。</div>
            <button v-for="brief in history" :key="brief.id" class="brief-choice" :class="{active:selectedBrief?.id===brief.id}" @click="selectBrief(brief)"><b>v{{brief.version}} · {{brief.status}}</b><small>{{brief.content_goal}} · {{brief.target_platform}}</small></button>
          </template>
        </template>
      </article>
    </div>

    <article v-if="selectedBrief" class="panel draft-panel">
      <div class="section-heading"><div><span class="eyebrow">M6.1 · AI DRAFT</span><h3>AI 草稿</h3><p>当前 Brief：v{{selectedBrief.version}} · {{selectedBrief.status}}</p></div><div><el-button type="primary" :loading="generating" @click="generateDraft()">生成 AI 草稿</el-button><el-button v-if="generationError&&pendingGenerationKey" :loading="generating" @click="generateDraft(true)">重试本次请求</el-button></div></div>
      <el-alert v-if="generationError" type="error" :closable="false" :title="generationError"/>
      <el-alert v-if="!draftLoading&&!draftHistory.length" type="info" :closable="false" title="Brief 尚未生成草稿。生成后会保留版本历史。"/>
      <div v-if="draftHistory.length" class="draft-layout"><aside class="draft-versions"><h4>草稿版本</h4><button v-for="draft in draftHistory" :key="draft.id" class="draft-choice" :class="{active:selectedDraft?.id===draft.id}" @click="openDraft(draft)"><el-tag size="small" :type="draftTagType(draft.status)">{{displayStatus(draft.status)}}</el-tag><b>v{{draft.draft_version}} · {{draft.title||'未命名草稿'}}</b><small>{{draft.provider_code||'—'}} · {{draft.model_name||'—'}}</small><small>{{draft.created_at}}</small></button></aside>
        <main v-loading="draftLoading" class="draft-detail"><template v-if="selectedDraft"><div class="draft-meta"><div><b>{{selectedDraft.title||structured.title||'未命名草稿'}}</b><small>Provider：{{selectedDraft.provider_code||'—'}} · Model：{{selectedDraft.model_name||'—'}} · Prompt：{{selectedDraft.prompt_version||'—'}}</small></div><el-tag :type="draftTagType(selectedDraft.status)">{{selectedDraft.status}}</el-tag></div>
          <small>Evidence Pack hash：{{selectedDraft.evidence_pack_hash||'—'}}</small>
          <el-alert v-if="blocked" type="error" :closable="false" title="该草稿被事实证据或风险门禁阻断，不能通过审核。" style="margin-top:12px"/>
          <section class="draft-section"><h4>内容预览</h4><template v-if="Object.keys(structured).length"><h5>{{structured.title||selectedDraft.title}}</h5><p v-if="structured.summary">{{structured.summary}}</p><div v-for="(section,index) in sections" :key="index" class="content-section"><b>{{section.heading}}</b><p v-for="(paragraph,pIndex) in section.paragraphs||[]" :key="pIndex">{{paragraph}}</p></div><div v-if="faqItems.length"><h5>FAQ</h5><div v-for="(item,index) in faqItems" :key="index" class="faq-item"><b>{{item.question}}</b><p>{{item.answer}}</p></div></div><p v-if="structured.callToAction"><b>行动建议：</b>{{structured.callToAction}}</p></template><pre v-else>{{selectedDraft.body||'模型未返回可解析内容。'}}</pre></section>
          <section class="draft-section"><h4>Evidence Pack</h4><el-alert v-if="!evidencePack.length" type="warning" :closable="false" title="当前没有足够的已核验证据，事实性内容将被阻断。"/><el-table v-else :data="evidencePack" size="small"><el-table-column prop="evidenceId" label="Evidence ID" min-width="220"/><el-table-column prop="type" label="类型" width="130"/><el-table-column prop="factType" label="事实类型" width="150"/><el-table-column prop="text" label="已核验内容" min-width="240"/></el-table></section>
          <section class="draft-section"><h4>Claim-Evidence Gate</h4><el-alert v-if="claimResult.blocked" type="error" :closable="false" title="事实证据校验未通过"/><el-alert v-else type="success" :closable="false" title="事实证据校验通过"/><ul v-if="claimResult.unsupportedClaims?.length" class="finding-list"><li v-for="claim in claimResult.unsupportedClaims" :key="claim">{{claim}}</li></ul></section>
          <section class="draft-section"><h4>风险扫描</h4><el-tag :type="riskResult.status==='CLEAN'?'success':riskResult.status==='WARNING'?'warning':'danger'">{{riskResult.status||'未扫描'}}</el-tag><el-empty v-if="!riskFindings.length" description="未发现风险项" :image-size="56"/><div v-for="(finding,index) in riskFindings" :key="index" class="finding"><el-tag size="small" :type="finding.severity==='BLOCKED'?'danger':'warning'">{{finding.severity}}</el-tag><b>{{finding.code}}</b><small>字段：{{finding.field||'—'}}</small><p>{{finding.suggestion}}</p><p v-if="finding.excerpt" class="muted-copy">{{finding.excerpt}}</p></div></section>
          <section v-if="selectedDraft.status==='FAILED'" class="draft-section"><h4>生成错误</h4><el-alert type="error" :closable="false" :title="metadata.error||'模型生成失败，请检查模型连接或稍后重试。'"/></section>
          <section class="draft-section review-section"><h4>人工审核</h4><el-input v-model="reviewComment" type="textarea" :rows="3" placeholder="填写审核意见（可选）" :disabled="!canSubmit&&!canDecide"/><div v-if="canSubmit"><el-button type="primary" :loading="reviewing" @click="review('SUBMIT')">提交审核</el-button></div><div v-else-if="canDecide"><el-button type="success" :loading="reviewing" @click="review('APPROVE')">通过</el-button><el-button type="danger" :loading="reviewing" @click="review('REJECT')">驳回</el-button></div><small v-else>{{blocked?'当前草稿已阻断，必须先修正内容后重新生成。':'当前状态只读，不允许执行审核操作。'}}</small></section>
        </template></main>
      </div>
    </article>
  </section>
</template>

<style scoped>
.content-execution{max-width:1320px}.draft-panel{margin-top:18px}.section-heading,.draft-meta{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}.section-heading h3{margin:4px 0}.section-heading p,.draft-meta small,.draft-detail>small{display:block;color:#718078;margin-top:5px}.draft-layout{display:grid;grid-template-columns:275px minmax(0,1fr);gap:18px;margin-top:18px}.draft-versions{border-right:1px solid #edf1ee;padding-right:12px}.draft-versions h4{margin-top:0}.brief-choice,.draft-choice{display:flex;width:100%;border:0;background:transparent;text-align:left;padding:12px 8px;border-radius:10px;flex-direction:column;gap:5px;cursor:pointer;color:#294136}.brief-choice:hover,.brief-choice.active,.draft-choice:hover,.draft-choice.active{background:#edf7f0}.brief-choice b,.draft-choice b{font-size:14px}.brief-choice small,.draft-choice small{color:#718078}.draft-choice .el-tag{width:max-content}.draft-detail{min-width:0}.draft-section{border-top:1px solid #edf1ee;margin-top:18px;padding-top:16px}.draft-section h4{margin:0 0 12px}.draft-section h5{margin:12px 0 7px}.content-section,.faq-item,.finding{padding:10px 0;border-bottom:1px solid #f0f3f1}.content-section:last-child,.faq-item:last-child,.finding:last-child{border-bottom:0}.content-section p,.faq-item p,.finding p{line-height:1.65;margin:6px 0}.finding{display:flex;align-items:flex-start;gap:8px;flex-wrap:wrap}.finding p{width:100%;margin-left:0}.finding-list{padding-left:20px;color:#b33}.review-section .el-input{margin-bottom:10px}.review-section small{display:block;color:#718078;margin-top:10px}.muted-copy{color:#718078}pre{white-space:pre-wrap;word-break:break-word;background:#f6f8f6;padding:12px;border-radius:8px}@media(max-width:900px){.draft-layout{grid-template-columns:1fr}.draft-versions{border-right:0;border-bottom:1px solid #edf1ee;padding:0 0 12px}.section-heading,.draft-meta{flex-direction:column}}
</style>
