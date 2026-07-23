<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { login, token } from './api'
import { useRoute, useRouter } from 'vue-router'
import { useMerchantContext } from './stores/merchantContext'
const loggedIn=ref(!!token());const email=ref('demo@localgrowth.test');const password=ref('Demo123!');const router=useRouter();const route=useRoute();const ctx=useMerchantContext();const isAuthEntry=computed(()=>route.path==='/'||route.path==='/login')
function returnToLogin(){loggedIn.value=false;if(route.path!=='/login')router.replace('/login')}
async function validateExistingToken(){if(!token()){loggedIn.value=false;return}try{await ctx.load();loggedIn.value=!!token();if(loggedIn.value&&isAuthEntry.value&&ctx.merchant.value)await router.replace(`/merchants/${ctx.merchant.value.id}/overview`)}catch{if(!token())returnToLogin()}}
async function submit(){try{await login(email.value,password.value);loggedIn.value=true;await ctx.load();if(ctx.merchant.value)router.replace(`/merchants/${ctx.merchant.value.id}/overview`)}catch(e:any){loggedIn.value=!!token();ElMessage.error(e.message)}}
onMounted(()=>{window.addEventListener('local-growth-auth-expired',returnToLogin);void validateExistingToken()})
onUnmounted(()=>window.removeEventListener('local-growth-auth-expired',returnToLogin))
</script>
<template><div v-if="!loggedIn||isAuthEntry" class="login-page"><div class="login-card"><span class="eyebrow">LOCAL AI GROWTH OS</span><h1>进入商家工作台</h1><p>以事实、证据和人工审核为基础的本地商家增长系统。</p><el-input v-model="email" placeholder="邮箱"/><el-input v-model="password" type="password" placeholder="密码" show-password/><el-button type="primary" size="large" @click="submit">登录</el-button></div></div><router-view v-else/></template>
