<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api'
import { useMerchantContext } from '../../stores/merchantContext'

const context = useMerchantContext()
const configs = ref<any[]>([])
const runs = ref<any[]>([])
const publications = ref<any[]>([])
const citationEvents = ref<any[]>([])
const experiments = ref<any[]>([])
const questions = ref<any[]>([])
const selectedConfigIds = ref<string[]>([])
const selectedQuestionIds = ref<string[]>([])
const running = ref(false)

const configForm = ref({
  name: '豆包官方联网观察',
  aiPlatform: 'DOUBAO',
  collectionChannel: 'OFFICIAL_API',
  providerCode: 'DOUBAO_OFFICIAL',
  apiBaseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
  modelName: '',
  secretEnvName: 'GEO_DOUBAO_API_KEY',
  webSearchEnabled: true,
  locationCountry: 'CN',
  locationText: '',
  requestOptions: { timeoutSeconds: 90, maxAttempts: 4, maxTokens: 4096 },
  autoCreateDraft: true,
  enabled: true,
  scheduleCron: ''
})

const publicationForm = ref({
  optimizationTaskId: null,
  platform: 'OFFICIAL_WEBSITE',
  title: '',
  url: '',
  publishedAt: null,
  contentSha256: '',
  status: 'ACTIVE',
  metadata: {}
})

function merchantId() {
  const merchant = context.merchant.value
  if (!merchant) throw new Error('当前未选择商家')
  return merchant.id
}

async function load() {
  if (!context.merchant.value) return
  const id = merchantId()
  ;[
    configs.value,
    runs.value,
    publications.value,
    citationEvents.value,
    experiments.value,
    questions.value
  ] = await Promise.all([
    api(`/merchants/${id}/observation-automation/collector-configs`),
    api(`/merchants/${id}/observation-automation/runs`),
    api(`/merchants/${id}/tracked-publications`),
    api(`/merchants/${id}/tracked-publications/citation-events`),
    api(`/merchants/${id}/retest-experiments`),
    api(`/merchants/${id}/consumer-questions?enabled=true`)
  ])
}

async function saveConfig() {
  await api(`/merchants/${merchantId()}/observation-automation/collector-configs`, {
    method: 'POST',
    body: JSON.stringify(configForm.value)
  })
  ElMessage.success('采集配置已保存。数据库只保存密钥环境变量名称。')
  await load()
}

async function runCollection() {
  if (!selectedConfigIds.value.length || !selectedQuestionIds.value.length) {
    ElMessage.warning('请选择采集配置和消费者问题')
    return
  }
  running.value = true
  try {
    await api(`/merchants/${merchantId()}/observation-automation/runs`, {
      method: 'POST',
      body: JSON.stringify({
        collectorConfigIds: selectedConfigIds.value,
        questionIds: selectedQuestionIds.value,
        autoCreateDraft: true
      })
    })
    ElMessage.success('采集完成，成功结果只创建为 DRAFT 观察')
    await load()
  } finally {
    running.value = false
  }
}

async function createPublication() {
  await api(`/merchants/${merchantId()}/tracked-publications`, {
    method: 'POST',
    body: JSON.stringify(publicationForm.value)
  })
  ElMessage.success('作品已登记')
  publicationForm.value.title = ''
  publicationForm.value.url = ''
  await load()
}

async function scanCitations() {
  const result = await api(
    `/merchants/${merchantId()}/tracked-publications/scan-citations`,
    { method: 'POST', body: '{}' }
  )
  ElMessage.success(`引用扫描完成：直接匹配 ${result.directMatchesProcessed}`)
  await load()
}

watch(() => context.merchant.value?.id, load)
onMounted(load)
</script>

<template>
  <section class="module-page">
    <div class="module-heading">
      <div>
        <span class="eyebrow">OBSERVATION, EVIDENCE & RETEST</span>
        <h2>自动观察与证据</h2>
        <p>
          官方 API 结果与真实 App 结果分开保存；自动结果默认 DRAFT，
          未经人工核验不会进入正式 M5 诊断。
        </p>
      </div>
      <el-button type="primary" :loading="running" @click="runCollection">
        运行官方 API 观察
      </el-button>
    </div>

    <div class="module-columns">
      <div class="panel">
        <h3>官方采集配置</h3>
        <el-input v-model="configForm.name" placeholder="配置名称" />
        <el-select v-model="configForm.aiPlatform">
          <el-option label="豆包" value="DOUBAO" />
          <el-option label="DeepSeek" value="DEEPSEEK" />
        </el-select>
        <el-select v-model="configForm.providerCode">
          <el-option label="豆包官方" value="DOUBAO_OFFICIAL" />
          <el-option label="DeepSeek 官方" value="DEEPSEEK_OFFICIAL" />
        </el-select>
        <el-input v-model="configForm.apiBaseUrl" placeholder="官方 API Base URL" />
        <el-input v-model="configForm.modelName" placeholder="模型名称" />
        <el-input
          v-model="configForm.secretEnvName"
          placeholder="密钥环境变量名称，不是密钥"
        />
        <el-switch
          v-model="configForm.webSearchEnabled"
          active-text="启用官方联网搜索"
        />
        <el-button @click="saveConfig">保存配置</el-button>
      </div>

      <div class="panel">
        <h3>运行范围</h3>
        <el-select
          v-model="selectedConfigIds"
          multiple
          placeholder="选择采集配置"
        >
          <el-option
            v-for="item in configs"
            :key="item.id"
            :label="`${item.name} · ${item.ai_platform}`"
            :value="item.id"
          />
        </el-select>
        <el-select
          v-model="selectedQuestionIds"
          multiple
          filterable
          placeholder="选择消费者问题"
        >
          <el-option
            v-for="item in questions"
            :key="item.id"
            :label="item.question_text"
            :value="item.id"
          />
        </el-select>
        <p class="muted-copy">
          API Key 必须通过服务端环境变量提供，页面和数据库都不保存真实密钥。
        </p>
      </div>
    </div>

    <div class="panel">
      <h3>最近采集运行</h3>
      <el-table :data="runs">
        <el-table-column prop="created_at" label="时间" />
        <el-table-column prop="status" label="状态" />
        <el-table-column prop="total_count" label="总数" />
        <el-table-column prop="success_count" label="成功" />
        <el-table-column prop="failure_count" label="失败" />
      </el-table>
    </div>

    <div class="module-columns">
      <div class="panel">
        <h3>登记发布作品</h3>
        <el-input v-model="publicationForm.title" placeholder="作品标题" />
        <el-input v-model="publicationForm.url" placeholder="公开 URL" />
        <el-select v-model="publicationForm.platform">
          <el-option label="官网" value="OFFICIAL_WEBSITE" />
          <el-option label="小红书" value="XIAOHONGSHU" />
          <el-option label="大众点评" value="DIANPING" />
          <el-option label="高德" value="AMAP" />
          <el-option label="其他" value="OTHER" />
        </el-select>
        <el-button @click="createPublication">登记并追踪</el-button>
        <el-button @click="scanCitations">扫描 AI 引用</el-button>
      </div>

      <div class="panel">
        <h3>复测实验</h3>
        <div v-for="item in experiments" :key="item.id" class="data-row">
          <b>{{ item.name }}</b>
          <small>{{ item.status }} · 重复 {{ item.repetitions }} 次</small>
        </div>
        <p class="muted-copy">
          复测比较必须固定问题、平台、渠道和地区。除直接引用外，只能陈述关联。
        </p>
      </div>
    </div>

    <div class="panel">
      <h3>作品引用事件</h3>
      <el-table :data="citationEvents">
        <el-table-column prop="title" label="作品" />
        <el-table-column prop="ai_platform" label="AI 平台" />
        <el-table-column prop="match_type" label="匹配类型" />
        <el-table-column prop="direct_citation" label="直接引用" />
        <el-table-column prop="last_seen_at" label="最近出现" />
      </el-table>
      <p class="muted-copy">DOMAIN_ONLY 不算作品直接引用。</p>
    </div>
  </section>
</template>
