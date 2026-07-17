<script setup lang="ts">
import { useMerchantContext } from '../../stores/merchantContext'
const c = useMerchantContext()
</script>
<template>
  <section class="module-page">
    <div class="module-heading"><div><span class="eyebrow">RETESTS & REPORTS</span><h2>复测与报告</h2><p>管理第14天、第30天复测节点，并进入已有报告入口。</p></div></div>
    <div class="panel"><h3>复测节点</h3><div v-if="!c.data.retests.length" class="empty-inline">正式诊断后会生成第14天和第30天复测节点。</div><div v-for="r in c.data.retests" :key="r.id" class="data-row"><b>{{ r.label === 'DAY_14' ? '第14天复测' : '第30天复测' }}</b><small>{{ r.planned_at }} · {{ r.status }}</small></div></div>
  </section>
</template>
