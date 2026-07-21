import { api } from '../../api'
const base=(merchantId:string)=>`/merchants/${merchantId}/content-execution`
export const plans=(m:string)=>api(`${base(m)}/plans`)
export const createPlan=(m:string,p:any)=>api(`${base(m)}/plans`,{method:'POST',body:JSON.stringify(p)})
export const updatePlan=(m:string,id:string,p:any)=>api(`${base(m)}/plans/${id}`,{method:'PUT',body:JSON.stringify(p)})
export const works=(m:string,plan:string)=>api(`${base(m)}/plans/${plan}/work-items`)
export const createWork=(m:string,plan:string,p:any)=>api(`${base(m)}/plans/${plan}/work-items`,{method:'POST',body:JSON.stringify(p)})
export const updateWork=(m:string,id:string,p:any)=>api(`${base(m)}/work-items/${id}`,{method:'PUT',body:JSON.stringify(p)})
export const briefs=(m:string,work:string)=>api(`${base(m)}/work-items/${work}/briefs`)
export const createBrief=(m:string,work:string,p:any)=>api(`${base(m)}/work-items/${work}/briefs`,{method:'POST',body:JSON.stringify(p)})
export const updateBrief=(m:string,id:string,p:any)=>api(`${base(m)}/briefs/${id}`,{method:'PUT',body:JSON.stringify(p)})
