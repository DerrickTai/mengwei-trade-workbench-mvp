const key='local-growth-token'
export const token=()=>localStorage.getItem(key)
export const logout=()=>localStorage.removeItem(key)
export async function api(path:string, options:RequestInit={}) { const isForm=options.body instanceof FormData; const headers={ ...(isForm?{}:{'Content-Type':'application/json'}), ...(token()?{Authorization:`Bearer ${token()}`}:{ }), ...(options.headers||{}) }; const res=await fetch(`/api/v1${path}`,{...options,headers}); const body=await res.json(); if(!res.ok) throw new Error(body.message||'请求失败'); return body }
export async function login(email:string,password:string){const result=await api('/auth/login',{method:'POST',body:JSON.stringify({email,password})});localStorage.setItem(key,result.accessToken);return result}
export async function apiBlob(path:string){const res=await fetch(`/api/v1${path}`,{headers:{...(token()?{Authorization:`Bearer ${token()}`}:{})}});if(!res.ok){const type=res.headers.get('content-type')||'';if(type.includes('application/json')){const body=await res.json();throw new Error(body.message||'请求失败')}throw new Error('下载失败')}const filename=(res.headers.get('content-disposition')||'').match(/filename=([^;]+)/)?.[1]?.replace(/"/g,'')||null;return{blob:await res.blob(),filename}}
export async function apiObjectUrl(path:string){const {blob}=await apiBlob(path);return URL.createObjectURL(blob)}
