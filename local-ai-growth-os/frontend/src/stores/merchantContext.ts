import { computed, reactive, ref } from 'vue'
import { api } from '../api'

export type Merchant = { id: string; name: string; industry?: string; city?: string; district?: string }
const merchant = ref<Merchant | null>(null)
const merchants = ref<Merchant[]>([])
const data = reactive({ brands: [] as any[], prompts: [] as any[], competitors: [] as any[], evidence: [] as any[], tasks: [] as any[], assets: [] as any[], retests: [] as any[], provider: null as any, report: null as any })
const loading = ref(false)
export function useMerchantContext(){
 const load = async (id?: string) => { loading.value=true; try { if(!merchants.value.length) merchants.value=await api('/merchants'); const target=merchants.value.find(x=>x.id===id)||merchants.value[0]; if(!target)return; merchant.value=target; [data.brands,data.prompts,data.competitors,data.evidence,data.tasks,data.assets,data.retests,data.provider]=await Promise.all([api(`/merchants/${target.id}/brands`),api(`/merchants/${target.id}/prompt-cases`),api(`/merchants/${target.id}/competitors`),api(`/merchants/${target.id}/evidence-observations`),api(`/merchants/${target.id}/optimization-tasks`),api(`/merchants/${target.id}/content-assets`),api(`/merchants/${target.id}/retest-plans`),api('/ai/provider-status')]) } finally { loading.value=false } }
 const refresh = async()=>merchant.value&&load(merchant.value.id)
 return { merchant, merchants, data, loading, load, refresh, hasMerchant: computed(()=>!!merchant.value) }
}
