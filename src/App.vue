<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {Search, Bell, Plus, Menu, X} from 'lucide-vue-next'
import {useSession} from './store'
import {api, send, type Board} from './api'

const route = useRoute(), router = useRouter(), session = useSession()
const boards = ref<Board[]>([]), query = ref(''), menu = ref(false)
const admin = computed(() => route.path.startsWith('/admin'))
onMounted(async () => {
  boards.value = await api<Board[]>('/boards').catch(() => []);
  if (!session.ready) session.refresh()
})

function search() {
  router.push({path: '/search', query: {q: query.value}});
  menu.value = false
}

async function logout() {
  await send('/auth/logout', 'POST');
  session.user = null;
  router.push('/')
}
</script>
<template>
  <div class="shell">
    <header class="topbar">
      <div class="top-inner">
        <RouterLink class="brand" to="/"><span class="brand-mark">同</span><span>同频<span
            class="brand-light">学习社区</span></span></RouterLink>
        <nav class="desktop-nav">
          <RouterLink to="/">讨论</RouterLink>
          <RouterLink to="/boards">板块</RouterLink>
          <RouterLink to="/featured">精华</RouterLink>
          <RouterLink v-if="session.user" to="/feed/following">关注</RouterLink>
        </nav>
        <form class="header-search" @submit.prevent="search">
          <Search :size="18"/>
          <input v-model="query" placeholder="搜索帖子标题" aria-label="搜索帖子标题"/></form>
        <div class="header-actions">
          <RouterLink v-if="session.user" to="/me/notifications" class="icon-link" title="通知">
            <Bell :size="20"/>
            <i v-if="session.unread" class="dot"/></RouterLink>
          <RouterLink v-if="session.user" to="/me" class="avatar" title="个人中心">{{
              session.user.username.slice(0, 1)
            }}
          </RouterLink>
          <RouterLink v-else class="login-link" to="/login">登录</RouterLink>
          <RouterLink to="/posts/new" class="button primary new-post">
            <Plus :size="18"/>
            发布
          </RouterLink>
          <button class="mobile-menu icon-link" @click="menu=!menu" :aria-label="menu?'关闭菜单':'打开菜单'">
            <X v-if="menu" :size="22"/>
            <Menu v-else :size="22"/>
          </button>
        </div>
      </div>
    </header>
    <div v-if="menu" class="mobile-drawer">
      <RouterLink to="/" @click="menu=false">讨论</RouterLink>
      <RouterLink to="/boards" @click="menu=false">板块</RouterLink>
      <RouterLink to="/featured" @click="menu=false">精华</RouterLink>
      <RouterLink to="/posts/new" @click="menu=false">发布帖子</RouterLink>
      <RouterLink v-if="session.user" to="/me" @click="menu=false">个人中心</RouterLink>
      <form @submit.prevent="search"><input v-model="query" placeholder="搜索帖子标题"/>
        <button>搜索</button>
      </form>
    </div>
    <div class="app-grid">
      <aside class="sidebar">
        <div class="sidebar-label">发现</div>
        <RouterLink to="/" class="side-link">全部讨论</RouterLink>
        <RouterLink to="/featured" class="side-link">精选内容</RouterLink>
        <RouterLink v-if="session.user" to="/feed/following" class="side-link">关注动态</RouterLink>
        <RouterLink v-if="session.user" to="/messages" class="side-link">私信</RouterLink>
        <RouterLink v-if="session.user" to="/me/drafts" class="side-link">草稿箱</RouterLink>
        <RouterLink v-if="session.user" to="/appeals" class="side-link">申诉</RouterLink>
        <div class="sidebar-label boards-label">学习板块</div>
        <RouterLink v-for="board in boards" :key="board.id" :to="'/boards/'+board.id" class="side-link">{{
            board.name
          }}
        </RouterLink>
        <div class="sidebar-bottom">
          <RouterLink v-if="session.user?.role !== 'student' && session.user" to="/admin">管理后台</RouterLink>
          <button v-if="session.user" @click="logout">退出登录</button>
          <span v-else>让每个问题都有回响。</span></div>
      </aside>
      <main class="main-content">
        <RouterView :key="route.fullPath"/>
      </main>
    </div>
    <footer>同频学习社区 · 保持好奇，认真交流 <span>请勿发布个人隐私或侵权内容</span></footer>
  </div>
</template>
