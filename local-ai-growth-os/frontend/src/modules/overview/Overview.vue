<script setup lang="ts">
import { computed } from 'vue'
import { useMerchantContext } from '../../stores/merchantContext'
const c = useMerchantContext()
const d = c.data
const realEvidence = computed(() => d.evidence.filter((x: any) => !x.is_demo).length)
const pendingAssets = computed(() => d.assets.filter((x: any) => x.review_status === 'DRAFT' || x.review_status === 'PENDING_REVIEW').length)
const publishedAssets = computed(() => d.assets.filter((x: any) => x.review_status === 'PUBLISHED').length)
</script>
<template><section class="module-page"><div class="module-heading"><div><span class="eyebrow">OVERVIEW</span><h2>总览</h2><p>查看商家当前资料、诊断、执行与复测进度。</p></div></div><div class="metric-grid"><div><small>问题数</small><strong>{{d.prompts.length}}</strong></div><div><small>真实证据</small><strong>{{realEvidence}}</strong></div><div><small>Gap / 任务</small><strong>{{d.tasks.length}}</strong></div><div><small>待审核内容</small><strong>{{pendingAssets}}</strong></div><div><small>已发布内容</small><strong>{{publishedAssets}}</strong></div></div><div class="next-action"><b>建议下一步</b><p v-if="!d.evidence.length">先在“问题与观察”导入真实AI回答。</p><p v-else-if="!d.tasks.length">完成正式诊断后生成30天执行计划。</p><p v-else>进入“执行中心”验收内容并登记发布。</p></div></section></template>
