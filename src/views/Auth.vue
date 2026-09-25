<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { send, type User } from '../api'
import { useSession } from '../store'
const route=useRoute(), router=useRouter(), session=useSession()
const register=computed(() => route.path==='/register'), email=ref(''), password=ref(''), username=ref(''), error=ref(''), busy=ref(false)
async function submit() { busy.value=true; error.value=''; try { session.user=await send<User>(register.value?'/auth/register':'/auth/login','POST',{ email:email.value,password:password.value,...register.value?{username:username.value}:{} }); await session.refresh(); router.push(String(route.query.next || (route.path==='/admin/login'?'/admin':'/'))) } catch (exception) { error.value=(exception as Error).message } finally { busy.value=false } }
</script>
<template><div class="auth-layout"><div class="auth-copy"><span class="brand-mark large">同</span><h1>每一次认真提问，<br/>都是进步的开始。</h1><p>在同频，找到愿意一起思考的人。</p></div><form class="auth-form" @submit.prevent="submit"><h2>{{ register?'加入同频':'欢迎回来' }}</h2><p>{{ register?'创建账号，开启学习交流。':'登录后继续你的讨论。' }}</p><label v-if="register">昵称<input v-model="username" required minlength="2" maxlength="40" placeholder="怎么称呼你"/></label><label>邮箱<input v-model="email" type="email" required placeholder="name@example.com"/></label><label>密码<input v-model="password" type="password" required minlength="8" placeholder="至少 8 位字符"/></label><p v-if="error" class="error">{{ error }}</p><button class="button primary full" :disabled="busy">{{ busy?'请稍候…':register?'创建账号':'登录' }}</button><div class="auth-switch">{{ register?'已有账号？':'还没有账号？' }} <RouterLink :to="register?'/login':'/register'">{{ register?'去登录':'立即注册' }}</RouterLink></div><small>使用本社区即表示你同意遵守友善交流原则，保护自己与他人的隐私。</small></form></div></template>
