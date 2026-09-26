<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { MessageCircle, Eye, Bookmark, ArrowRight } from 'lucide-vue-next'
import { api, date, type Board, type Post, type Tag } from '../api'
const route=useRoute(), router=useRouter()
const posts=ref<Post[]>([]), boards=ref<Board[]>([]), tags=ref<Tag[]>([]), loading=ref(true), error=ref(''), hasNext=ref(false), searchTitle=ref('')
const page=computed(() => { const value=Number(route.query.page || 1); return Number.isInteger(value) && value>0 ? value : 1 })
const type=ref(''), tag=ref(''), sort=ref('latest'), solved=ref('')
const isBoard=computed(() => route.path==='/boards')
const board=computed(() => boards.value.find(value => String(value.id)===route.params.id))
const title=computed(() => route.path==='/featured'?'精华区':route.path==='/search'?'搜索结果':route.path.startsWith('/tags/')?`# ${tags.value.find(value => String(value.id)===route.params.id)?.name || '标签'}`:board.value?.name || (isBoard.value?'学习板块':'一起，把问题想明白。'))
const description=computed(() => board.value?.description || (route.path==='/featured'?'值得反复阅读的讨论与经验。':route.path==='/search'?`关于「${route.query.q || ''}」的讨论`:'提问、分享与讨论，让知识在交流中生长。'))
async function load() {
  loading.value=true; error.value=''
  const query=new URLSearchParams({ page:String(page.value),sort:sort.value })
  if (route.query.q) query.set('q',String(route.query.q))
  if (route.params.id && route.path.startsWith('/boards/')) query.set('board',String(route.params.id))
  if (route.params.id && route.path.startsWith('/tags/')) query.set('tag',String(route.params.id))
  if (route.path==='/featured') query.set('featured','true')
  if (type.value) query.set('type',type.value)
  if (tag.value) query.set('tag',tag.value)
  if (solved.value) query.set('solved',solved.value)
  try { const result=await api<Post[]>('/search?'+query); hasNext.value=result.length>20; posts.value=result.slice(0,20) } catch (exception) { error.value=(exception as Error).message; posts.value=[]; hasNext.value=false } finally { loading.value=false }
}
function changePage(nextPage:number) { router.push({ query: { ...route.query, page: nextPage===1 ? undefined : String(nextPage) } }) }
function search() { router.push({ path:'/search', query:searchTitle.value.trim() ? { q:searchTitle.value.trim() } : {} }) }
onMounted(async () => { [boards.value,tags.value]=await Promise.all([api<Board[]>('/boards').catch(() => []),api<Tag[]>('/tags').catch(() => [])]); load() })
watch(() => route.query.q, value => { searchTitle.value=String(value || '') }, { immediate:true })
watch([type,tag,sort,solved],() => { if (page.value>1) changePage(1); else load() })
watch(() => route.fullPath,load)
</script>
<template>
  <div class="feed-page"><section class="intro"><div class="intro-copy"><h1>{{ title }}</h1><p>{{ description }}</p><RouterLink v-if="!board && !isBoard" class="text-link" to="/boards">探索学习板块 <ArrowRight :size="17"/></RouterLink></div><div class="intro-art" aria-hidden="true"><span class="art-circle"></span><span class="art-book">知<br/>识</span><span class="art-line"></span><span class="art-note">一起讨论<br/>共同进步</span></div></section>
    <section v-if="isBoard" class="board-directory"><RouterLink v-for="item in boards" :key="item.id" :to="'/boards/'+item.id" class="board-item"><span class="board-icon">{{ item.name[0] }}</span><span><strong>{{ item.name }}</strong><small>{{ item.description }}</small></span><ArrowRight :size="18"/></RouterLink></section>
    <div class="feed-layout"><div class="feed-main"><div class="section-heading"><h2>{{ route.path==='/featured'?'精华讨论':route.path==='/search'?'标题搜索结果':'最新讨论' }}</h2><span>本页 {{ posts.length }} 条内容</span></div><form class="title-search" @submit.prevent="search"><input v-model="searchTitle" maxlength="100" placeholder="输入帖子标题关键词" aria-label="搜索帖子标题"/><button class="button primary" type="submit">搜索标题</button></form><div class="filters"><select v-model="type" aria-label="帖子类型"><option value="">全部类型</option><option value="question">问答</option><option value="discussion">讨论</option><option value="experience">经验</option></select><select v-model="tag" aria-label="标签"><option value="">全部标签</option><option v-for="item in tags" :value="item.id" :key="item.id">{{ item.name }}</option></select><select v-model="solved" aria-label="解决状态"><option value="">全部状态</option><option value="false">待解决</option><option value="true">已解决</option></select><select v-model="sort" aria-label="排序"><option value="latest">最新发布</option><option value="hot">最热讨论</option></select></div>
      <div v-if="loading" class="empty">正在加载讨论…</div><div v-else-if="error" class="empty">暂时无法连接服务，请确认后端和数据库已启动。<button class="retry" @click="load">重试</button></div><div v-else-if="!posts.length" class="empty">这里还没有讨论。发起第一篇帖子吧。<RouterLink to="/posts/new">去发布</RouterLink></div>
      <div v-else class="post-list"><RouterLink v-for="post in posts" :key="post.id" :to="'/posts/'+post.id" class="post-row"><div class="post-top"><span class="type-badge" :class="post.type">{{ post.type==='question'?'问答':post.type==='experience'?'经验':'讨论' }}</span><span v-if="post.is_top" class="mini-badge">置顶</span><span v-if="post.is_featured" class="mini-badge featured">精华</span><span v-if="post.is_solved" class="mini-badge solved">已解决</span></div><h3>{{ post.title }}</h3><p>{{ post.content }}</p><div class="post-meta"><span class="mini-avatar">{{ post.username?.slice(0,1) }}</span><span>{{ post.username }}</span><span>{{ post.board_name }}</span><span>{{ date(post.created_at) }}</span><span class="meta-spacer"></span><span><MessageCircle :size="15"/>{{ post.reply_count }}</span><span><Eye :size="15"/>{{ post.view_count }}</span></div></RouterLink></div><nav v-if="!loading && !error && (hasNext || page>1)" class="pager" aria-label="帖子分页"><button :disabled="page===1" @click="changePage(page-1)">上一页</button><span>第 {{ page }} 页</span><button :disabled="!hasNext" @click="changePage(page+1)">下一页</button></nav></div>
      <aside class="feed-aside"><div class="aside-section"><h3>学习板块</h3><RouterLink v-for="item in boards" :key="item.id" :to="'/boards/'+item.id"><span>{{ item.name }}</span><ArrowRight :size="15"/></RouterLink></div><div class="aside-section"><h3>热门标签</h3><div class="tag-cloud"><RouterLink v-for="item in tags" :key="item.id" :to="'/tags/'+item.id"># {{ item.name }}</RouterLink></div></div><div class="aside-note"><Bookmark :size="19"/><p>好的问题值得被看见，好的回答值得被收藏。</p></div></aside></div>
  </div>
</template>
