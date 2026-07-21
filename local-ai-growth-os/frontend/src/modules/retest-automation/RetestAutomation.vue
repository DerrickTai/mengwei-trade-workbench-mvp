<template>
  <section class="page">
    <header class="page-header">
      <div>
        <h2>自动复测与统计</h2>
        <p>用重复采样、对照问题和连续周期判断，避免把一次 AI 命中误判为稳定提升。</p>
      </div>
      <el-button type="primary" @click="openCreate">创建复测实验</el-button>
    </header>

    <el-alert
      title="自动观察仍为 DRAFT；统计报告是证据摘要，不是确定因果结论。"
      type="warning" show-icon :closable="false" />

    <el-table :data="experiments" v-loading="loading" style="margin-top:16px">
      <el-table-column prop="name" label="实验" min-width="220" />
      <el-table-column prop="automation_state" label="状态" width="130">
        <template #default="scope"><el-tag>{{ scope.row.automation_state }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="sample_count_per_cell" label="每单元采样" width="110" />
      <el-table-column prop="completed_schedule_count" label="已完成" width="90" />
      <el-table-column prop="schedule_count" label="总节点" width="90" />
      <el-table-column prop="next_due_at" label="下次复测" min-width="180" />
      <el-table-column label="操作" width="230">
        <template #default="scope">
          <el-button link type="primary" @click="showReport(scope.row)">报告</el-button>
          <el-button link type="success" @click="activate(scope.row)">启用</el-button>
          <el-button link type="warning" @click="pause(scope.row)">暂停</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="reportVisible" title="复测时间线与指标" size="72%">
      <el-table :data="reportRows" v-loading="reportLoading">
        <el-table-column prop="day_offset" label="发布后" width="90">
          <template #default="scope">D+{{ scope.row.day_offset }}</template>
        </el-table-column>
        <el-table-column prop="status" label="节点状态" width="110" />
        <el-table-column prop="cohort" label="组别" width="100" />
        <el-table-column prop="metric_name" label="指标" min-width="220" />
        <el-table-column label="结果" width="110">
          <template #default="scope">{{ percent(scope.row.metric_value) }}</template>
        </el-table-column>
        <el-table-column label="95% 区间" width="170">
          <template #default="scope">
            {{ percent(scope.row.ci_low) }} ~ {{ percent(scope.row.ci_high) }}
          </template>
        </el-table-column>
        <el-table-column prop="valid_sample_count" label="有效样本" width="100" />
        <el-table-column label="提示" min-width="180">
          <template #default="scope">
            <el-tag v-if="scope.row.context_drift" type="danger">口径变化</el-tag>
            <el-tag v-else-if="Number(scope.row.volatility_score) >= 0.8" type="warning">波动较高</el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <el-dialog v-model="createVisible" title="创建自动复测实验" width="720px">
      <el-form label-position="top">
        <el-form-item label="实验名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="发布时刻"><el-date-picker v-model="form.publicationTime" type="datetime" /></el-form-item>
        <el-form-item label="基线快照 ID"><el-input v-model="form.baselineSnapshotId" placeholder="必须是发布前基线" /></el-form-item>
        <el-form-item label="目标问题 ID（每行一个）"><el-input v-model="form.targetQuestions" type="textarea" :rows="4" /></el-form-item>
        <el-form-item label="对照问题 ID（每行一个）"><el-input v-model="form.controlQuestions" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="Collector 配置 ID（每行一个）"><el-input v-model="form.collectorConfigs" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="复测日"><el-input v-model="form.dayOffsets" placeholder="3,7,14,30" /></el-form-item>
        <el-form-item label="每个单元采样次数"><el-input-number v-model="form.sampleCount" :min="1" :max="20" /></el-form-item>
        <el-form-item><el-switch v-model="form.automationEnabled" active-text="创建后启用" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible=false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="createExperiment">创建</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'

const route = useRoute()
const merchantId = String(route.params.merchantId)
const loading = ref(false)
const saving = ref(false)
const reportLoading = ref(false)
const createVisible = ref(false)
const reportVisible = ref(false)
const experiments = ref<any[]>([])
const reportRows = ref<any[]>([])
const form = reactive({
  name: '', publicationTime: new Date(), baselineSnapshotId: '',
  targetQuestions: '', controlQuestions: '', collectorConfigs: '',
  dayOffsets: '3,7,14,30', sampleCount: 5, automationEnabled: false
})

function authHeaders() {
  // Adapt to the repository's existing auth helper / axios instance.
  return { 'Content-Type': 'application/json', Authorization: localStorage.getItem('token') || '' }
}
function lines(value: string) { return value.split(/\r?\n|,/).map(v => v.trim()).filter(Boolean) }
function percent(value: unknown) {
  const n = Number(value)
  return Number.isFinite(n) ? `${(n * 100).toFixed(1)}%` : '—'
}
async function api(path: string, options: RequestInit = {}) {
  const response = await fetch(`/api/v1/merchants/${merchantId}/retest-automation${path}`, {
    ...options, headers: { ...authHeaders(), ...(options.headers || {}) }
  })
  if (!response.ok) throw new Error(await response.text())
  return response.status === 204 ? null : response.json()
}
async function load() {
  loading.value = true
  try { experiments.value = await api('/experiments') }
  catch (e:any) { ElMessage.error(e.message || '加载失败') }
  finally { loading.value = false }
}
function openCreate() { createVisible.value = true }
async function createExperiment() {
  saving.value = true
  try {
    await api('/experiments', { method: 'POST', body: JSON.stringify({
      name: form.name,
      publicationTime: new Date(form.publicationTime).toISOString(),
      baselineSnapshotId: form.baselineSnapshotId || null,
      targetQuestionIds: lines(form.targetQuestions),
      controlQuestionIds: lines(form.controlQuestions),
      collectorConfigIds: lines(form.collectorConfigs),
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'Asia/Shanghai',
      localTime: '09:00', dayOffsets: lines(form.dayOffsets).map(Number),
      sampleCountPerCell: form.sampleCount, automationEnabled: form.automationEnabled
    }) })
    ElMessage.success('复测实验已创建')
    createVisible.value = false
    await load()
  } catch (e:any) { ElMessage.error(e.message || '创建失败') }
  finally { saving.value = false }
}
async function showReport(row:any) {
  reportVisible.value = true; reportLoading.value = true
  try { reportRows.value = await api(`/experiments/${row.id}/report`) }
  catch (e:any) { ElMessage.error(e.message || '报告加载失败') }
  finally { reportLoading.value = false }
}
async function activate(row:any) { await api(`/experiments/${row.id}/activate`, { method:'POST' }); await load() }
async function pause(row:any) { await api(`/experiments/${row.id}/pause`, { method:'POST' }); await load() }

onMounted(load)
</script>

<style scoped>
.page { padding: 20px; }
.page-header { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; margin-bottom:16px; }
.page-header h2 { margin:0 0 8px; }
.page-header p { margin:0; color:var(--el-text-color-secondary); }
</style>
