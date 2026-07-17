<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { login, token } from './api'
import { useRouter } from 'vue-router'
import { useMerchantContext } from './stores/merchantContext'
const loggedIn=ref(!!token());const email=ref('demo@localgrowth.test');const password=ref('Demo123!');const router=useRouter();const ctx=useMerchantContext()
async function submit(){try{await login(email.value,password.value);loggedIn.value=true;await ctx.load();if(ctx.merchant.value)router.push(`/merchants/${ctx.merchant.value.id}/overview`)}catch(e:any){ElMessage.error(e.message)}}
</script>
<template><div v-if="!loggedIn" class="login-page"><div class="login-card"><span class="eyebrow">LOCAL AI GROWTH OS</span><h1>进入商家工作台</h1><p>以事实、证据和人工审核为基础的本地商家增长系统。</p><el-input v-model="email" placeholder="邮箱"/><el-input v-model="password" type="password" placeholder="密码" show-password/><el-button type="primary" size="large" @click="submit">登录</el-button></div></div><router-view v-else/></template>
