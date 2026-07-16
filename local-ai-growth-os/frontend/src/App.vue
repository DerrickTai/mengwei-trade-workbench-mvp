<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, apiBlob, apiObjectUrl, login, logout, token } from './api'
import { diagnosticReady } from './readiness'

type Merchant = { id: string, name: string, industry: string, city: string, district?: string, status: string }
type AssetMeta = { id: string, originalFilename: string, previewUrl: string | null }

const loggedIn = ref(!!token())
const email = ref('demo@localgrowth.test')
const password = ref('Demo123!')
const merchants = ref<Merchant[]>([])
const selected = ref<Merchant | null>(null)
const brands = ref<any[]>([])
const prompts = ref<any[]>([])
const competitors = ref<any[]>([])
const evidence = ref<any[]>([])
const executionTasks = ref<any[]>([])
const contentAssets = ref<any[]>([])
const retestPlans = ref<any[]>([])
const result = ref<any>(null)
const formalResult = ref<any>(null)
const executionAnchor = ref<HTMLElement | null>(null)
const loading = ref(false)
const assetMap = ref<Record<string, AssetMeta>>({})

const merchantForm = ref({ name: '', industry: '', city: '佛山', district: '' })
const brandForm = ref({ name: '', website: '', aliases: '', services: '', claims: '' })
const promptForm = ref({ question: '佛山九江哪家洗脸洗头靠谱？', category: 'RECOMMENDATION', city: '佛山', district: '九江镇', intentLevel: 'HIGH', enabled: true, sortOrder: 0, locale: 'zh-CN' })
const competitorForm = ref({ name: '', aliases: '' })
const observationForm = ref({ questionId: '', platform: '豆包', modelName: '豆包搜索', sourceType: 'MANUAL_APP_OBSERVATION', searchEnabled: true, rawAnswer: '', capturedAt: new Date().toISOString().slice(0, 16), citationLinks: '', screenshotAssetId: '', operatorNotes: '' })
const publicationForm = ref({ assetId: '', platform: 'XIAOHONGSHU', accountName: '', publishedUrl: '', publishedAt: new Date().toISOString().slice(0, 16), screenshotAssetId: '', publishStatus: 'PUBLISHED', indexStatus: 'PENDING_CHECK', notes: '' })
const showPublicationForm = ref(false)
const generationMode = ref('TEMPLATE_MOCK')
const generationChannel = ref('XIAOHONGSHU')
const realDraft = ref<any>(null)
const qualityAsset = ref<any>(null)
const qualityForm = ref({ factualityScore: 5, platformFitScore: 5, usefulnessScore: 5, reviewerNotes: '', editedContent: '' })
const providerStatus = ref<any>(null)
const workspaceTab = ref<'diagnosis' | 'execution'>('diagnosis')
const generationState = ref<'idle' | 'calling' | 'success' | 'failed'>('idle')
const generationError = ref('')

const ready = computed(() => diagnosticReady(brands.value.length, prompts.value.length))
const hasDemoEvidence = computed(() => evidence.value.some(item => item.is_demo))
const formalScore = computed(() => formalResult.value?.scoreGroups?.find((item: any) => item.formalScorePublished)?.score ?? null)
const formalSources = computed(() => formalResult.value?.scoreGroups ?? [])

async function loadMerchants() {
  merchants.value = await api('/merchants')
  if (merchants.value.length && !selected.value) {
    await choose(merchants.value[0])
  }
}

async function choose(merchant: Merchant) {
  selected.value = merchant
  result.value = null
  formalResult.value = null
  workspaceTab.value = executionTasks.value.length || contentAssets.value.length ? 'execution' : 'diagnosis'
  assetMap.value = {}
  ;[brands.value, prompts.value, competitors.value, evidence.value, executionTasks.value, contentAssets.value, retestPlans.value] = await Promise.all([
    api(`/merchants/${merchant.id}/brands`),
    api(`/merchants/${merchant.id}/prompt-cases`),
    api(`/merchants/${merchant.id}/competitors`),
    api(`/merchants/${merchant.id}/evidence-observations`),
    api(`/merchants/${merchant.id}/optimization-tasks`),
    api(`/merchants/${merchant.id}/content-assets`),
    api(`/merchants/${merchant.id}/retest-plans`)
  ])
  if (!observationForm.value.questionId && prompts.value.length) {
    observationForm.value.questionId = prompts.value[0].id
  }
  await hydrateAssets(evidence.value)
  providerStatus.value = await api('/ai/provider-status').catch(() => null)
  workspaceTab.value = executionTasks.value.length || contentAssets.value.length ? 'execution' : 'diagnosis'
}

async function hydrateAssets(rows: any[]) {
  const ids = [...new Set(rows.map(item => item.screenshot_asset_id).filter(Boolean))]
  await Promise.all(ids.map(async id => {
    if (assetMap.value[id]) return
    try {
      const asset = await api(`/assets/${id}`)
      asset.previewUrl = await apiObjectUrl(`/assets/${id}/content`)
      assetMap.value[id] = asset
    } catch {
      assetMap.value[id] = { id, originalFilename: '预览不可用', previewUrl: null }
    }
  }))
}

async function submitLogin() {
  try {
    await login(email.value, password.value)
    loggedIn.value = true
    await loadMerchants()
  } catch (error: any) {
    ElMessage.error(error.message)
  }
}

async function addMerchant() {
  try {
    const merchant = await api('/merchants', { method: 'POST', body: JSON.stringify(merchantForm.value) })
    merchants.value.unshift(merchant)
    merchantForm.value = { name: '', industry: '', city: '佛山', district: '' }
    await choose(merchant)
    ElMessage.success('商家档案已创建')
  } catch (error: any) {
    ElMessage.error(error.message)
  }
}

async function add(path: string, form: any, target: any) {
  try {
    const value = await api(`/merchants/${selected.value!.id}${path}`, { method: 'POST', body: JSON.stringify(form.value) })
    target.value.push(value)
    ElMessage.success('已保存')
  } catch (error: any) {
    ElMessage.error(error.message)
  }
}

async function run() {
  try {
    loading.value = true
    result.value = await api('/diagnostic-runs', { method: 'POST', body: JSON.stringify({ merchantId: selected.value!.id, provider: 'MOCK' }) })
    ElMessage.success('演示诊断已完成')
  } catch (error: any) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}

async function importObservation() {
  try {
    loading.value = true
    const payload = { ...observationForm.value, merchantId: selected.value!.id, capturedAt: new Date(observationForm.value.capturedAt).toISOString() }
    const saved = await api('/evidence-observations', { method: 'POST', body: JSON.stringify(payload) })
    evidence.value.unshift(saved)
    await hydrateAssets([saved])
    ElMessage.success('真实回答证据已导入并完成品牌识别')
  } catch (error: any) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}

async function uploadImage(file: File) {
  try {
    const data = new FormData()
    data.append('file', file)
    const asset = await api('/assets', { method: 'POST', body: data })
    asset.previewUrl = await apiObjectUrl(`/assets/${asset.id}/content`)
    observationForm.value.screenshotAssetId = asset.id
    assetMap.value[asset.id] = asset
    ElMessage.success('截图已上传，可安全关联至证据')
  } catch (error: any) {
    ElMessage.error(error.message)
  }
  return false
}

async function runFormal() {
  try {
    loading.value = true
    formalResult.value = await api('/formal-diagnostic-runs', { method: 'POST', body: JSON.stringify({ merchantId: selected.value!.id }) })
    await nextTick()
    executionAnchor.value?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    ElMessage.success(formalResult.value.formalScorePublished ? '正式报告已生成' : '已生成样本不足报告')
  } catch (error: any) {
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}

async function generateExecutionPlan() {
  if (!formalResult.value?.reportId) return
  try {
    loading.value = true
    executionTasks.value = await api(`/diagnostic-reports/${formalResult.value.reportId}/optimization-tasks`, { method: 'POST' })
    retestPlans.value = await api(`/diagnostic-runs/${formalResult.value.runId}/retest-plans`, { method: 'POST' })
    workspaceTab.value = 'execution'
    ElMessage.success('已生成30天任务计划和复测节点')
  } catch (error: any) { ElMessage.error(error.message) } finally { loading.value = false }
}

async function generateTaskContent(task: any) {
  try {
    const asset = await api(`/optimization-tasks/${task.id}/content-assets`, { method: 'POST', body: JSON.stringify({ channel: task.recommended_channels?.[0] || 'WEBSITE' }) })
    contentAssets.value.unshift(asset)
    ElMessage.success('内容草稿已生成，等待人工审核')
  } catch (error: any) { ElMessage.error(error.message) }
}

async function generateRealContent(task: any) {
  try { loading.value = true; generationState.value = 'calling'; generationError.value = ''; realDraft.value = await api(`/optimization-tasks/${task.id}/real-content`, { method: 'POST', body: JSON.stringify({ channel: generationChannel.value }) }); if (selected.value && !realDraft.value.generationBlocked) contentAssets.value = await api(`/merchants/${selected.value.id}/content-assets`); generationState.value = realDraft.value.generationBlocked ? 'failed' : 'success'; generationError.value = realDraft.value.reason || ''; ElMessage.success(realDraft.value.generationBlocked ? '生成失败：资料不足' : '生成成功'); } catch (error: any) { generationState.value = 'failed'; generationError.value = error.message; ElMessage.error(error.message) } finally { loading.value = false }
}
function openDraft() { workspaceTab.value = 'execution'; document.querySelector('.real-draft')?.scrollIntoView({ behavior: 'smooth', block: 'center' }) }

async function reviewContent(asset: any, status: string) {
  try { const updated = await api(`/content-assets/${asset.id}/review`, { method: 'PATCH', body: JSON.stringify({ status }) }); Object.assign(asset, updated); ElMessage.success('审核状态已更新') }
  catch (error: any) { ElMessage.error(error.message) }
}
async function submitQualityReview(asset: any, status: string) { try { const updated = await api(`/content-assets/${asset.id}/review`, { method: 'PATCH', body: JSON.stringify({ status, ...qualityForm.value }) }); Object.assign(asset, updated); qualityAsset.value = null; ElMessage.success(status === 'REJECTED' ? '内容已拒绝并记录原因' : '人工质量验收已保存') } catch (error: any) { ElMessage.error(error.message) } }

async function publishContent(asset: any) {
  try {
    const form = publicationForm.value
    const saved = await api(`/content-assets/${asset.id}/publications`, { method: 'POST', body: JSON.stringify({ ...form, publishedAt: new Date(form.publishedAt).toISOString() }) })
    asset.latest_publish_status = saved.publish_status
    asset.latest_index_status = saved.index_status
    contentAssets.value = await api(`/merchants/${selected.value!.id}/content-assets`)
    ElMessage.success('发布记录已保存')
  } catch (error: any) { ElMessage.error(error.message) }
}

async function downloadReport(kind: 'pdf' | 'zip') {
  if (!formalResult.value?.reportId) return
  try {
    const file = await apiBlob(`/reports/${formalResult.value.reportId}/${kind === 'pdf' ? 'pdf' : 'evidence.zip'}`)
    const url = URL.createObjectURL(file.blob)
    const link = document.createElement('a')
    link.href = url
    link.download = file.filename || `geo-report.${kind === 'pdf' ? 'pdf' : 'zip'}`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
  } catch (error: any) {
    ElMessage.error(error.message)
  }
}

function signOut() {
  logout()
  loggedIn.value = false
  selected.value = null
  merchants.value = []
}

onMounted(() => {
  if (loggedIn.value) {
    loadMerchants().catch(signOut)
  }
})
</script>

<template>
  <main v-if="!loggedIn" class="login">
    <section>
      <p class="eyebrow">LOCAL AI GROWTH OS</p>
      <h1>把 AI 可见度<br>变成可交付诊断</h1>
      <p>商家建档、问题测试、品牌出现统计、竞品对照和可解释优化报告。</p>
      <el-alert title="演示账号已预填：demo@localgrowth.test / Demo123!" type="info" :closable="false" />
      <el-form @submit.prevent="submitLogin">
        <el-input v-model="email" placeholder="邮箱" />
        <el-input v-model="password" type="password" show-password placeholder="密码" />
        <el-button type="primary" native-type="submit">进入诊断工作台</el-button>
      </el-form>
    </section>
  </main>
  <main v-else class="app-shell">
    <aside>
      <div class="logo">L<span>AI</span>G</div>
      <p class="eyebrow">诊断工作台</p>
      <el-button class="new" type="primary" @click="selected = null">＋ 新建商家</el-button>
      <div class="merchant-list">
        <button v-for="merchant in merchants" :key="merchant.id" :class="{ active: selected?.id === merchant.id }" @click="choose(merchant)">
          <b>{{ merchant.name }}</b>
          <small>{{ merchant.city }} · {{ merchant.industry }}</small>
        </button>
      </div>
      <button class="logout" @click="signOut">退出登录</button>
    </aside>
    <section class="content">
      <template v-if="!selected">
        <header>
          <div>
            <p class="eyebrow">第一步</p>
            <h2>建立商家诊断档案</h2>
            <p>资料属于当前企业租户，其他租户不可读取。</p>
          </div>
        </header>
        <el-form label-position="top" class="form-grid">
          <el-form-item label="商家名称"><el-input v-model="merchantForm.name" /></el-form-item>
          <el-form-item label="行业"><el-input v-model="merchantForm.industry" placeholder="例如：全屋定制" /></el-form-item>
          <el-form-item label="城市"><el-input v-model="merchantForm.city" /></el-form-item>
          <el-form-item label="区域"><el-input v-model="merchantForm.district" /></el-form-item>
          <el-button type="primary" @click="addMerchant">创建并继续</el-button>
        </el-form>
      </template>
      <template v-else>
        <header>
          <div>
            <p class="eyebrow">{{ selected.city }} · {{ selected.industry }}</p>
            <h2>{{ selected.name }}</h2>
            <p>保存原始回答、截图与引用后，生成可追溯的 GEO 真实诊断报告。</p>
          </div>
          <el-tag type="success">GEO 证据链 v0.3</el-tag>
        </header>
        <el-alert v-if="hasDemoEvidence" title="演示数据，不代表真实平台结果；正式评分与正式客户报告默认排除演示数据。" type="warning" :closable="false" />
        <div class="steps">
          <span :class="{ done: brands.length }">1 品牌知识</span>
          <span :class="{ done: prompts.length >= 20 }">2 问题集（{{ prompts.length }}/20）</span>
          <span :class="{ done: competitors.length >= 3 }">3 竞争品牌（{{ competitors.length }}/3）</span>
          <span :class="{ done: evidence.length }">4 真实证据（{{ evidence.length }}）</span>
          <span :class="{ done: formalResult }">5 正式报告</span>
        </div>
        <div class="workspace-tabs"><el-button :type="workspaceTab === 'diagnosis' ? 'primary' : 'default'" @click="workspaceTab = 'diagnosis'">诊断与报告</el-button><el-button :type="workspaceTab === 'execution' ? 'primary' : 'default'" :disabled="!executionTasks.length && !contentAssets.length && !formalResult" @click="workspaceTab = 'execution'">执行计划</el-button></div>
        <section v-if="formalResult" ref="executionAnchor" class="next-step-banner">
          <div>
            <span class="next-step-kicker">诊断已完成 · 下一步</span>
            <h3>把报告里的 Gap 变成30天执行计划</h3>
            <p>系统会生成优化任务、内容草稿和第14天/第30天复测节点；内容发布仍由你人工审核和登记。</p>
          </div>
          <el-button type="primary" size="large" :loading="loading" @click="generateExecutionPlan">生成30天任务计划 →</el-button>
        </section>
        <div class="columns">
          <section class="card">
            <h3>品牌知识库</h3>
            <p>仅保存可核验的真实信息；它是后续内容与报告的事实基础。</p>
            <div class="scroll-list compact-list">
              <div v-for="brand in brands" :key="brand.id" class="item">
                <b>{{ brand.name }}</b>
                <small>{{ brand.services || '未填写服务范围' }}</small>
              </div>
            </div>
            <el-form label-position="top">
              <el-input v-model="brandForm.name" placeholder="品牌名称" />
              <el-input v-model="brandForm.aliases" placeholder="别名，用逗号分隔" />
              <el-input v-model="brandForm.services" placeholder="服务项目，用逗号分隔" />
              <el-input v-model="brandForm.claims" placeholder="已验证主张 / 证据摘要" />
              <el-input v-model="brandForm.website" placeholder="官网或证据链接" />
              <el-button @click="add('/brands', brandForm, brands)">保存品牌档案</el-button>
            </el-form>
          </section>
          <section class="card">
            <h3>Prompt 测试问题</h3>
            <p>正式评分至少需要20个启用问题。按来源、地域和意图保存。</p>
            <div class="scroll-list prompt-list">
              <div v-for="(prompt, index) in prompts" :key="prompt.id" class="item question-item">
                <span class="item-index">{{ String(index + 1).padStart(2, '0') }}</span>
                <div><b>{{ prompt.question }}</b>
                <small>{{ prompt.category }} · {{ prompt.city || selected.city }} {{ prompt.district || '' }} · {{ prompt.intent_level }}</small></div>
              </div>
            </div>
            <el-form label-position="top">
              <el-input v-model="promptForm.question" type="textarea" />
              <el-select v-model="promptForm.category">
                <el-option v-for="category in ['RECOMMENDATION', 'PRICE', 'PAIN_POINT', 'COMPARISON', 'LOCATION']" :key="category" :value="category" :label="category" />
              </el-select>
              <el-button @click="add('/prompt-cases', promptForm, prompts)">加入问题集</el-button>
            </el-form>
          </section>
          <section class="card">
            <h3>竞争品牌</h3>
            <p>用于可见度对照；不生成贬损性文案。</p>
            <div class="scroll-list compact-list">
              <div v-for="competitor in competitors" :key="competitor.id" class="item">
                <b>{{ competitor.name }}</b>
                <small>{{ competitor.aliases || '无别名' }}</small>
              </div>
            </div>
            <el-form label-position="top">
              <el-input v-model="competitorForm.name" placeholder="竞争品牌名称" />
              <el-input v-model="competitorForm.aliases" placeholder="别名，用逗号分隔" />
              <el-button @click="add('/competitors', competitorForm, competitors)">加入竞品</el-button>
            </el-form>
          </section>
          <section class="card">
            <h3>人工导入真实回答</h3>
            <p>保存平台、联网状态、原始回答、时间、引用和截图；Mock 与演示证据不进入正式报告。</p>
            <div class="scroll-list evidence-list">
              <div v-for="item in evidence.slice(0, 8)" :key="item.id" class="item evidence-item">
                <b>
                  {{ item.platform }} · {{ item.source_type }}
                  <el-tag v-if="item.is_demo" size="small" type="warning">演示数据</el-tag>
                </b>
                <small>{{ item.question }}</small>
                <small>{{ item.model_name }} · {{ item.captured_at }} · 品牌 {{ item.brand_mention_count }} 次 · 首次位置 {{ item.brand_first_position ?? '未出现' }}</small>
                <details class="evidence-details">
                  <summary>查看已保存原始回答</summary>
                  <p class="raw-answer">{{ item.raw_answer }}</p>
                  <small>联网：{{ item.search_enabled ? '是' : '否' }} · 引用：{{ item.citation_links || '未填写' }}</small>
                  <small>备注：{{ item.operator_notes || '未填写' }}</small>
                </details>
                <a v-if="item.screenshot_asset_id && assetMap[item.screenshot_asset_id]?.previewUrl" class="asset-link" :href="assetMap[item.screenshot_asset_id].previewUrl || undefined" target="_blank" rel="noreferrer">
                  已保存截图：{{ assetMap[item.screenshot_asset_id].originalFilename }}
                </a>
              </div>
            </div>
            <small v-if="evidence.length > 8" class="list-more">已保存 {{ evidence.length }} 条，以上展示最近 8 条；正式报告使用全部非演示证据。</small>
            <el-form label-position="top">
              <el-select v-model="observationForm.questionId" placeholder="选择问题">
                <el-option v-for="prompt in prompts" :key="prompt.id" :value="prompt.id" :label="prompt.question" />
              </el-select>
              <el-select v-model="observationForm.platform">
                <el-option value="豆包" label="豆包" />
                <el-option value="DeepSeek" label="DeepSeek" />
                <el-option value="小红书小点 AI" label="小红书小点 AI" />
                <el-option value="大众点评 AI" label="大众点评 AI" />
                <el-option value="美团 AI" label="美团 AI" />
              </el-select>
              <el-select v-model="observationForm.sourceType">
                <el-option value="MANUAL_APP_OBSERVATION" label="人工 App 观察" />
                <el-option value="API_MODEL" label="授权 API 模型" />
                <el-option value="SEARCH_API" label="搜索 API" />
              </el-select>
              <el-input v-model="observationForm.rawAnswer" type="textarea" placeholder="粘贴未经改写的原始回答" />
              <el-input v-model="observationForm.citationLinks" placeholder="引用链接，逗号分隔" />
              <el-upload :auto-upload="false" :show-file-list="false" accept="image/png,image/jpeg,image/webp" :on-change="(file: any) => uploadImage(file.raw)">
                <el-button>上传截图（PNG/JPG/WebP）</el-button>
              </el-upload>
              <small v-if="observationForm.screenshotAssetId" class="hint">截图已关联，可在保存后用于证据预览和 ZIP 导出。</small>
              <el-button :disabled="!observationForm.questionId || !observationForm.rawAnswer" @click="importObservation">导入并识别</el-button>
            </el-form>
          </section>
        </div>
        <section class="runbar">
          <div>
            <b>生成报告</b>
            <small>Mock 可继续演示；正式报告只读取 `API_MODEL`、`MANUAL_APP_OBSERVATION`、`SEARCH_API`，并按来源独立评分。</small>
          </div>
          <div class="actions">
            <el-button :disabled="!ready" :loading="loading" @click="run">运行 Mock 演示</el-button>
            <el-button type="primary" size="large" :disabled="prompts.length < 20 || !evidence.length" :loading="loading" @click="runFormal">生成 GEO 真实报告</el-button>
          </div>
        </section>
        <section v-if="workspaceTab === 'execution' && (formalResult || executionTasks.length || contentAssets.length)" class="execution-panel">
          <div class="execution-head">
            <div><p class="eyebrow">GEO EXECUTION LOOP V0.3</p><h3>从 Gap 到执行</h3><p>任务、内容、审核和复测都保留人工确认；系统不自动登录或发布平台。</p></div>
            <el-button type="primary" :loading="loading" @click="generateExecutionPlan">生成30天任务计划</el-button>
          </div>
          <div class="execution-grid">
            <div class="execution-card"><div class="execution-card-title"><b>优化任务</b><span>{{ executionTasks.length }} 项</span></div>
              <div class="execution-guide"><b>操作引导</b><span>1. 选择平台</span><span>2. 选择优化任务</span><span>3. 点击“用AI生成内容”</span><span>4. 在内容资产中进行人工验收</span></div>
              <div class="real-model-toolbar"><el-select v-model="generationChannel" size="small"><el-option v-for="p in ['WEBSITE','XIAOHONGSHU','DIANPING','DOUYIN','WECHAT','ZHIHU']" :key="p" :value="p" :label="p" /></el-select><el-tag size="small" :type="providerStatus?.configured ? 'success' : 'warning'">DeepSeek：{{ providerStatus?.configured ? '可用' : '未配置' }}</el-tag></div>
              <el-alert v-if="generationState === 'calling'" title="正在调用DeepSeek……" type="info" :closable="false"/><el-alert v-if="generationState === 'success'" title="生成成功" type="success" :closable="false"><template #default><el-button link type="success" @click="openDraft">查看草稿</el-button></template></el-alert><el-alert v-if="generationState === 'failed'" :title="`生成失败：${generationError || '请检查商家事实资料或Provider配置'}`" type="error" :closable="false"/>
              <div v-for="task in executionTasks" :key="task.id" class="task-row"><div><b>{{ task.title }}</b><small>{{ task.task_type }} · {{ task.priority }} · {{ task.status }}</small></div><div class="asset-actions"><el-button size="small" @click="generateTaskContent(task)">Mock</el-button><el-button size="small" type="primary" :loading="loading && generationState === 'calling'" :disabled="!providerStatus?.configured" @click="generateRealContent(task)">用AI生成内容</el-button></div></div>
            </div>
            <div class="execution-card"><div class="execution-card-title"><b>内容资产</b><span>{{ contentAssets.length }} 条</span></div>
              <div v-for="asset in contentAssets.slice(0, 6)" :key="asset.id" class="task-row"><div><b>{{ asset.title }}</b><small>{{ asset.channel }} · {{ asset.review_status }} · 发布 {{ asset.latest_publish_status || '未登记' }} · 收录 {{ asset.latest_index_status || '未知' }}</small></div><div class="asset-actions"><el-button v-if="asset.review_status === 'DRAFT'" size="small" @click="qualityAsset = asset; qualityForm.editedContent = asset.content || ''">人工验收</el-button><el-button v-if="asset.review_status === 'PENDING_REVIEW'" size="small" @click="reviewContent(asset, 'APPROVED')">通过审核</el-button><el-button v-if="asset.review_status === 'APPROVED'" size="small" type="primary" @click="publicationForm.assetId = asset.id; showPublicationForm = true">登记发布</el-button></div></div>
              <el-form v-if="qualityAsset" label-position="top" class="publication-form"><el-alert title="人工质量验收：确认事实、平台适配和可用性后才能提交审核" type="warning" :closable="false"/><el-input v-model="qualityForm.editedContent" type="textarea" :rows="6" placeholder="可在这里修改模型草稿"/><div class="quality-scores"><el-input-number v-model="qualityForm.factualityScore" :min="1" :max="5"/><el-input-number v-model="qualityForm.platformFitScore" :min="1" :max="5"/><el-input-number v-model="qualityForm.usefulnessScore" :min="1" :max="5"/></div><el-input v-model="qualityForm.reviewerNotes" type="textarea" placeholder="验收备注 / 拒绝原因"/><div class="actions"><el-button type="primary" @click="submitQualityReview(qualityAsset, 'PENDING_REVIEW')">确认并提交审核</el-button><el-button type="danger" @click="submitQualityReview(qualityAsset, 'REJECTED')">拒绝</el-button><el-button @click="qualityAsset = null">取消</el-button></div></el-form>
              <el-form v-if="showPublicationForm" label-position="top" class="publication-form"><el-input v-model="publicationForm.accountName" placeholder="账号名称" /><el-input v-model="publicationForm.publishedUrl" placeholder="发布链接（可选）" /><el-select v-model="publicationForm.platform"><el-option v-for="p in ['WEBSITE','XIAOHONGSHU','DIANPING','DOUYIN','WECHAT','ZHIHU']" :key="p" :value="p" :label="p" /></el-select><el-select v-model="publicationForm.publishStatus"><el-option v-for="s in ['PENDING','PUBLISHED','FAILED','REMOVED','LIMITED']" :key="s" :value="s" :label="s" /></el-select><el-select v-model="publicationForm.indexStatus"><el-option v-for="s in ['UNKNOWN','PENDING_CHECK','INDEXED','NOT_INDEXED']" :key="s" :value="s" :label="s" /></el-select><el-input v-model="publicationForm.notes" type="textarea" placeholder="发布备注" /><div class="actions"><el-button type="primary" :disabled="!publicationForm.accountName" @click="publishContent(contentAssets.find((x:any) => x.id === publicationForm.assetId))">保存发布登记</el-button><el-button @click="showPublicationForm = false">取消</el-button></div></el-form>
            </div>
            <div class="execution-card"><div class="execution-card-title"><b>复测节点</b><span>{{ retestPlans.length }} 个</span></div>
              <div v-for="plan in retestPlans" :key="plan.id" class="task-row"><div><b>{{ plan.label === 'DAY_14' ? '第14天复测' : '第30天复测' }}</b><small>{{ plan.planned_at }} · {{ plan.status }}</small></div><el-tag size="small">待执行</el-tag></div>
            </div>
          </div>
          <div v-if="realDraft" class="real-draft"><div class="execution-card-title"><b>真实模型生成结果</b><span>{{ realDraft.provider }} · {{ realDraft.model }} · {{ realDraft.promptVersion }}</span></div><el-alert v-if="realDraft.generationBlocked" :title="realDraft.reason" type="warning" :closable="false"/><div v-if="realDraft.missingFacts?.length" class="missing-facts">缺少事实：{{ realDraft.missingFacts.join('、') }}</div><pre v-if="realDraft.generatedContent">{{ realDraft.generatedContent }}</pre><div v-if="realDraft.riskFlags?.length" class="risk-flags">风险标记：{{ realDraft.riskFlags.join('、') }}</div></div>
        </section>
        <section v-if="result" class="report">
          <div class="score">
            <small>GEO SCORE · GEO_SCORE_V1</small>
            <strong>{{ result.score }}</strong>
            <span>/ 100</span>
            <p>{{ result.brandMentions }} / {{ result.answerCount }} 条回答出现品牌</p>
          </div>
          <article>
            <div class="report-note">仅为 MockProvider 演示结果。正式报告应接入已授权的真实模型、保存模型版本与运行时间。</div>
            <pre>{{ result.reportMarkdown }}</pre>
          </article>
        </section>
        <section v-if="formalResult" class="report">
          <div class="score">
            <small>GEO SCORE · V2</small>
            <strong>{{ formalScore ?? '—' }}</strong>
            <span>{{ formalScore !== null ? '/ 100' : '样本不足' }}</span>
            <p>{{ formalResult.evidenceCount }} 条真实证据 / {{ formalResult.questionCount }} 个问题</p>
            <button class="download-link" @click="downloadReport('pdf')">下载 PDF</button>
            <button class="download-link" @click="downloadReport('zip')">下载证据 ZIP</button>
          </div>
          <article>
            <div class="report-note">每个数字均可追溯至原始回答；不同来源不混合评分，Mock 与演示数据已默认排除。</div>
            <div class="report-source">
              <div><span>本次报告取数</span><strong>{{ formalResult.evidenceCount }} 条非演示真实证据</strong></div>
              <div><span>问题范围</span><strong>{{ formalResult.questionCount }} 个启用问题</strong></div>
              <div><span>正式来源</span><strong>{{ formalSources.map((item: any) => `${item.sourceType} ${item.sampleCount}条`).join(' · ') || '暂无可评分来源' }}</strong></div>
              <p>明确排除：MOCK 来源、is_demo=true 演示证据。报告评分来自你在各 AI App 中采集并保存的原始回答，不是系统凭空生成。</p>
            </div>
            <pre>{{ formalResult.reportMarkdown }}</pre>
          </article>
        </section>
      </template>
    </section>
  </main>
</template>
