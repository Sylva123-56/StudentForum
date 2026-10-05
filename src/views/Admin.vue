<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, date, send } from '../api'
import { useSession } from '../store'
import ConfirmDialog from './ConfirmDialog.vue'
type AdminBoard = { id: number; name: string; slug: string; description: string; sort_order: number; status: string }
type AdminTag = { id: number; name: string; status: string }
const route=useRoute(), router=useRouter(), session=useSession(), section=computed(() => String(route.params.section || 'dashboard'))
const dashboard=ref<any>({}), users=ref<any[]>([]), posts=ref<any[]>([]), reports=ref<any[]>([]), boards=ref<AdminBoard[]>([]), tags=ref<AdminTag[]>([]), logs=ref<any[]>([]), announcement=ref(''), appeals=ref<any[]>([]), reputationLogs=ref<any[]>([]), error=ref('')
const announcements=ref<any[]>([]), announcementEditing=ref<any>(null), announcementDraft=ref(''), announcementToRemove=ref<any>(null), announcementDeleting=ref(false)
const appealHandlingId=ref<number>(), appealNote=ref(''), appealSubmitting=ref(false)
const boardForm=ref({id:0,name:'',slug:'',description:'',sortOrder:0}), boardFormOpen=ref(false)
const tagForm=ref({id:0,name:''}), tagFormOpen=ref(false)
const roleForm=ref({id:0,username:'',role:'student',boardId:0}), roleFormOpen=ref(false)
type Paged = { items:any[]; hasNext:boolean }
const searchInput=ref(''), keyword=ref(''), page=ref(1), hasNext=ref(false), loading=ref(false)
const checkinData=ref<any>({}), focusData=ref<any>({}), roomData=ref<any>({})
const checkingSettings=ref(false)
const settingsForm=ref({checkin_points:2,streak_bonus:1,streak_bonus_days:7,focus_points:1,focus_daily_cap:10,plan_task_points:1,plan_task_daily_cap:10,plan_complete_points:10,allow_makeup:true})
// 与后端 AdminStudyController.NUMBERS 的 [最小,最大] 保持一致，避免前端先发出必被拒绝的请求
const settingsLimits:Record<string,[number,number]>={checkin_points:[0,100],streak_bonus:[0,100],streak_bonus_days:[1,365],focus_points:[0,100],focus_daily_cap:[0,1440],plan_task_points:[0,100],plan_task_daily_cap:[0,1440],plan_complete_points:[0,500]}
const focusRange=ref('week'), roomStatus=ref('active')
const sourceLabel:Record<string,string>={timer:'番茄钟',manual:'手动补记',room:'自习室',plan:'计划联动'}
const tagPage=ref(1), tagHasNext=ref(false), boardOptions=ref<AdminBoard[]>([])
const searchHints:Record<string,string> = {reports:'搜索举报人昵称或原因',posts:'搜索帖子标题',users:'搜索用户昵称',boards:'搜索板块名称、标识或标签名称',points:'搜索操作类型或详情',appeals:'搜索申诉人昵称',reputation:'搜索用户昵称'}
const searchHint=computed(() => searchHints[section.value] || '')
function resetQuery() { searchInput.value=''; keyword.value=''; page.value=1; hasNext.value=false; tagPage.value=1; tagHasNext.value=false }
function query(currentPage=page.value) { const params=new URLSearchParams({page:String(currentPage)}); if (keyword.value) params.set('keyword',keyword.value); return '?'+params }
function applySearch() { keyword.value=searchInput.value.trim(); page.value=1; tagPage.value=1; load() }
function changePage(next:number) { page.value=next; load() }
function changeTagPage(next:number) { tagPage.value=next; load() }
const enabledBoards=computed(() => boardOptions.value.filter(item => item.status==='enabled'))
function openRoleForm(user:any) {
  roleForm.value={id:user.id,username:user.username,role:user.role,boardId:user.moderator_board_id || 0}
  roleFormOpen.value=true
}
async function saveRole() {
  const form=roleForm.value
  if (form.role==='moderator' && !form.boardId) return ElMessage.warning('请选择版主负责的板块')
  await change('/admin/users/'+form.id+'/role','PATCH',{role:form.role,boardId:form.role==='moderator'?form.boardId:null})
  roleFormOpen.value=false
}
const sections=[['dashboard','概览'],['reports','举报处理'],['posts','内容管理'],['users','用户管理'],['boards','板块与标签'],['points','操作记录'],['announcements','系统公告'],['appeals','申诉管理'],['reputation','信誉分'],['checkin','打卡规则'],['focus','专注数据'],['study-rooms','自习室治理']]
async function load() {
  if (!session.user || !['admin','moderator'].includes(session.user.role)) return
  const admin=session.user.role==='admin', current=section.value
  loading.value=true; error.value=''
  try {
    if (current==='dashboard') { dashboard.value=await api('/admin/dashboard'); hasNext.value=false }
    else if (current==='reports') { const result=await api<Paged>('/admin/reports'+query()); reports.value=result.items; hasNext.value=result.hasNext }
    else if (current==='posts') { const result=await api<Paged>('/admin/posts'+query()); posts.value=result.items; hasNext.value=result.hasNext }
    else if (current==='users' && admin) { const [result,enabled]=await Promise.all([api<Paged>('/admin/users'+query()),api<AdminBoard[]>('/boards')]); users.value=result.items; hasNext.value=result.hasNext; boardOptions.value=enabled }
    else if (current==='boards' && admin) { const [boardResult,tagResult]=await Promise.all([api<Paged>('/admin/boards'+query()),api<Paged>('/admin/tags'+query(tagPage.value))]); boards.value=boardResult.items as AdminBoard[]; tags.value=tagResult.items as AdminTag[]; hasNext.value=boardResult.hasNext; tagHasNext.value=tagResult.hasNext }
    else if (current==='points' && admin) { const result=await api<Paged>('/admin/points'+query()); logs.value=result.items; hasNext.value=result.hasNext }
    else if (current==='announcements' && admin) { const result=await api<Paged>('/admin/announcements'+query()); announcements.value=result.items; hasNext.value=result.hasNext }
    else if (current==='appeals' && admin) { const result=await api<Paged>('/admin/appeals'+query()); appeals.value=result.items; hasNext.value=result.hasNext }
    else if (current==='reputation' && admin) { const result=await api<Paged>('/admin/reputation'+query()); reputationLogs.value=result.items; hasNext.value=result.hasNext }
    else if (current==='checkin') {
      const result=await api<any>('/admin/study-tools/checkin')
      checkinData.value=result; hasNext.value=false
      const saved=result.settings || {}
      const next:any={...settingsForm.value}
      for (const key of Object.keys(settingsLimits)) if (saved[key]!==undefined && saved[key]!==null) next[key]=Number(saved[key])
      next.allow_makeup=saved.allow_makeup===undefined ? true : Boolean(saved.allow_makeup)
      settingsForm.value=next
    }
    else if (current==='focus') { focusData.value=await api<any>('/admin/study-tools/focus?range='+focusRange.value); hasNext.value=false }
    else if (current==='study-rooms') { roomData.value=await api<any>('/admin/study-tools/study-rooms?status='+roomStatus.value); hasNext.value=false }
    else hasNext.value=false
  } catch (exception) { error.value=(exception as Error).message }
  finally { loading.value=false }
}
onMounted(load); watch(section,() => { resetQuery(); load() }); watch([focusRange,roomStatus],() => { if (['focus','study-rooms'].includes(section.value)) load() })
async function saveSettings() {
  const payload:any={}
  for (const key of Object.keys(settingsLimits)) {
    const value=Number((settingsForm.value as any)[key])
    const [min,max]=settingsLimits[key]
    if (!Number.isFinite(value) || value<min || value>max) return ElMessage.warning(`${key} 需要是 ${min} 到 ${max} 之间的数字`)
    payload[key]=value
  }
  payload.allow_makeup=Boolean(settingsForm.value.allow_makeup)
  checkingSettings.value=true
  try { await change('/admin/study-tools/checkin/settings','PATCH',payload) }
  finally { checkingSettings.value=false }
}
async function deleteSession(session:any) {
  const confirmed=await ElMessageBox.confirm(`删除用户 ${session.username} 这条 ${session.duration_minutes} 分钟的专注记录？积分不会回滚。`,'删除专注记录',{confirmButtonText:'删除',cancelButtonText:'取消',type:'warning'}).then(() => true).catch(() => false)
  if (confirmed) await change('/admin/study-tools/focus/'+session.id,'DELETE',{})
}
async function deleteRoom(room:any) {
  const confirmed=await ElMessageBox.confirm(`删除自习室「${room.name}」？成员与留言会一并移除。`,'删除自习室',{confirmButtonText:'删除',cancelButtonText:'取消',type:'warning'}).then(() => true).catch(() => false)
  if (confirmed) await change('/admin/study-tools/study-rooms/'+room.id,'DELETE',{})
}
async function change(path:string,method:string,value:object) { try { await send(path,method,value); ElMessage.success('操作已完成'); await load() } catch (exception) { ElMessage.error((exception as Error).message) } }
async function process(report:any,action:string) { const result=await ElMessageBox.prompt('填写处理备注（可留空）','处理举报',{inputValue:''}).catch(() => null); if (result) change(`/admin/reports/${report.id}`,'PATCH',{action,note:result.value}) }
function openBoardForm(board?:AdminBoard) {
  boardForm.value=board?{id:board.id,name:board.name,slug:board.slug,description:board.description || '',sortOrder:board.sort_order ?? 0}:{id:0,name:'',slug:'',description:'',sortOrder:0}
  boardFormOpen.value=true
}
async function saveBoard() {
  const form=boardForm.value, name=form.name.trim(), slug=form.slug.trim()
  if (name.length<2 || name.length>50) return ElMessage.warning('板块名称需为 2 到 50 个字')
  if (!/^[a-z0-9-]{2,80}$/.test(slug)) return ElMessage.warning('板块标识只能使用 2-80 位小写字母、数字或短横线')
  const payload={name,slug,description:form.description.trim(),sortOrder:Number(form.sortOrder) || 0}
  await change(form.id?'/admin/boards/'+form.id:'/admin/boards',form.id?'PATCH':'POST',payload)
  boardFormOpen.value=false
}
function openTagForm(tag?:AdminTag) {
  tagForm.value=tag?{id:tag.id,name:tag.name}:{id:0,name:''}
  tagFormOpen.value=true
}
async function saveTag() {
  const form=tagForm.value, name=form.name.trim()
  if (name.length<2 || name.length>40) return ElMessage.warning('标签名称需为 2 到 40 个字')
  await change(form.id?'/admin/tags/'+form.id:'/admin/tags',form.id?'PATCH':'POST',{name})
  tagFormOpen.value=false
}
async function toggleBoard(board:AdminBoard) {
  const next=board.status==='enabled'?'disabled':'enabled'
  const confirmed=next==='disabled'?await ElMessageBox.confirm(`停用板块“${board.name}”？历史帖子会保留，但该板块将不再出现在前台。`,'停用板块',{confirmButtonText:'停用',cancelButtonText:'取消',type:'warning'}).then(() => true).catch(() => false):true
  if (confirmed) await change('/admin/boards/'+board.id+'/status','PATCH',{status:next})
}
async function toggleTag(tag:AdminTag) {
  const next=tag.status==='enabled'?'disabled':'enabled'
  const confirmed=next==='disabled'?await ElMessageBox.confirm(`停用标签“${tag.name}”？历史帖子关联会保留，但该标签将不再可选。`,'停用标签',{confirmButtonText:'停用',cancelButtonText:'取消',type:'warning'}).then(() => true).catch(() => false):true
  if (confirmed) await change('/admin/tags/'+tag.id+'/status','PATCH',{status:next})
}
async function adjust(user:any) { const result=await ElMessageBox.prompt('输入积分增减数值（-1000 到 1000，不能为 0）','调整积分',{inputValidator:value => { const amount=Number(value); return Number.isInteger(amount) && amount!==0 && Math.abs(amount)<=1000 ? true : '请输入 -1000 到 1000 之间且不为 0 的整数' }}).catch(() => null); if (result) change(`/admin/users/${user.id}/points`,'POST',{amount:Number(result.value)}) }
async function handleAppeal(item:any,status:string) {
  const note=appealNote.value.trim()
  if (!note || appealSubmitting.value) return
  appealSubmitting.value=true
  error.value=''
  try {
    await send('/admin/appeals/'+item.id,'PATCH',{status,note})
    appealHandlingId.value=undefined
    appealNote.value=''
    await load()
  } catch (exception) { error.value=(exception as Error).message }
  finally { appealSubmitting.value=false }
}
async function adjustReputation() { const user=await ElMessageBox.prompt('用户 ID','调整信誉分').catch(() => null); if (!user) return; const amount=await ElMessageBox.prompt('增减分值（-100 到 100）','调整信誉分').catch(() => null); if (!amount) return; const reason=await ElMessageBox.prompt('调整理由','调整信誉分').catch(() => null); if (reason) change('/admin/reputation/'+user.value,'PATCH',{amount:Number(amount.value),reason:reason.value}) }
async function publishAnnouncement() { const message=announcement.value.trim(); if (message.length<5) return ElMessage.warning('公告内容至少需要 5 个字'); if (message.length>255) return ElMessage.warning('公告内容不能超过 255 个字'); await change('/admin/announcements','POST',{message}); announcement.value='' }
function startEditAnnouncement(item:any) { announcementEditing.value=item; announcementDraft.value=item.message }
function cancelEditAnnouncement() { announcementEditing.value=null; announcementDraft.value='' }
async function saveAnnouncementEdit() {
  const item=announcementEditing.value, message=announcementDraft.value.trim()
  if (!item) return
  if (message.length<5) return ElMessage.warning('公告内容至少需要 5 个字')
  if (message.length>255) return ElMessage.warning('公告内容不能超过 255 个字')
  try { await send('/admin/announcements/'+item.id,'PATCH',{message}); ElMessage.success('公告已更新'); cancelEditAnnouncement(); await load() }
  catch (exception) { ElMessage.error((exception as Error).message) }
}
async function confirmRemoveAnnouncement() {
  const item=announcementToRemove.value
  if (!item || announcementDeleting.value) return
  announcementDeleting.value=true
  try { await send('/admin/announcements/'+item.id,'DELETE'); ElMessage.success('公告已删除'); await load() }
  catch (exception) { ElMessage.error((exception as Error).message) }
  finally { announcementDeleting.value=false; announcementToRemove.value=null }
}
</script>
<template><div class="admin-page" v-if="session.user && ['admin','moderator'].includes(session.user.role)"><div class="page-heading"><span>社区治理</span><h1>管理后台</h1><p>保持讨论有序，让每一份认真回答被看见。</p></div><div class="admin-tabs"><RouterLink v-for="item in sections.filter(item=>session.user?.role==='admin' || !['users','boards','points','announcements','appeals','reputation','checkin','focus','study-rooms'].includes(item[0]))" :key="item[0]" :to="item[0]==='dashboard'?'/admin':'/admin/'+item[0]" :class="{active:section===item[0]}">{{ item[1] }}</RouterLink></div><p v-if="error" class="error">{{ error }}</p>
  <form v-if="searchHint" class="admin-search" @submit.prevent="applySearch"><input v-model="searchInput" maxlength="60" type="search" :placeholder="searchHint" :aria-label="searchHint"/><button class="button primary" type="submit" :disabled="loading">{{ loading?'搜索中…':'搜索' }}</button><button v-if="keyword" class="button" type="button" @click="searchInput=''; applySearch()">清除</button></form>
  <p v-if="keyword" class="admin-search-hint">正在搜索「{{ keyword }}」</p>
  <div v-if="section==='dashboard'" class="stats-grid"><div v-for="item in [['用户',dashboard.users],['帖子',dashboard.posts],['回复',dashboard.replies],['今日新帖',dashboard.today],['待处理举报',dashboard.pending]]" :key="item[0]"><strong>{{ item[1] ?? '—' }}</strong><span>{{ item[0] }}</span></div></div>
  <template v-if="section==='reports'"><h2>举报队列</h2><el-table :data="reports" stripe><el-table-column prop="id" label="ID" width="65"/><el-table-column prop="reporter_name" label="举报人" width="110"/><el-table-column prop="target_type" label="对象" width="90"/><el-table-column prop="target_id" label="对象 ID" width="90"/><el-table-column prop="reason" label="原因"/><el-table-column prop="status" label="状态"/><el-table-column label="处理" min-width="260"><template #default="scope"><template v-if="scope.row.status==='pending'"><el-button size="small" @click="process(scope.row,'reject')">驳回</el-button><el-button size="small" @click="process(scope.row,'hide')">隐藏</el-button><el-button size="small" type="danger" @click="process(scope.row,'delete')">删除</el-button><el-button v-if="session.user?.role==='admin'" size="small" @click="process(scope.row,'mute')">禁言</el-button></template><span v-else>{{ scope.row.handle_note }}</span></template></el-table-column></el-table></template>
  <template v-if="section==='posts'"><h2>内容管理</h2><el-table :data="posts" stripe><el-table-column prop="title" label="帖子" min-width="220"/><el-table-column prop="username" label="作者" width="100"/><el-table-column prop="status" label="状态" width="90"/><el-table-column label="操作" min-width="240"><template #default="scope"><el-button size="small" @click="change(`/admin/posts/${scope.row.id}/feature`,'PATCH',{featured:!scope.row.is_featured})">{{ scope.row.is_featured?'取消精华':'加精' }}</el-button><el-button size="small" @click="change(`/admin/posts/${scope.row.id}/top`,'PATCH',{top:!scope.row.is_top})">{{ scope.row.is_top?'取消置顶':'置顶' }}</el-button><el-button size="small" @click="change(`/admin/posts/${scope.row.id}/status`,'PATCH',{status:scope.row.status==='published'?'hidden':'published'})">{{ scope.row.status==='published'?'隐藏':'恢复' }}</el-button></template></el-table-column></el-table></template>
  <template v-if="section==='users'"><h2>用户管理</h2>
    <form v-if="roleFormOpen" class="admin-form" @submit.prevent="saveRole"><h3>设置「{{ roleForm.username }}」的角色</h3><div class="form-row"><label>角色<select v-model="roleForm.role"><option value="student">学生</option><option value="moderator">版主</option><option value="admin">管理员</option></select></label><label v-if="roleForm.role==='moderator'">负责板块<select v-model.number="roleForm.boardId"><option :value="0" disabled>请选择板块</option><option v-for="item in enabledBoards" :key="item.id" :value="item.id">{{ item.name }}</option></select></label></div><p class="admin-form-hint">版主只能管理所负责板块的帖子与举报。</p><div class="v2-inline"><button class="button primary" type="submit">保存</button><button class="button" type="button" @click="roleFormOpen=false">取消</button></div></form>
    <el-table :data="users" stripe><el-table-column prop="username" label="昵称"/><el-table-column prop="email" label="邮箱" min-width="180"/><el-table-column prop="role" label="角色" width="90"/><el-table-column label="负责板块" width="130"><template #default="scope">{{ scope.row.role==='moderator'?(scope.row.moderator_board_name || '未设置'):'—' }}</template></el-table-column><el-table-column prop="status" label="状态" width="80"/><el-table-column prop="points" label="积分" width="70"/><el-table-column label="操作" min-width="280"><template #default="scope"><el-button size="small" @click="change(`/admin/users/${scope.row.id}/status`,'PATCH',{status:scope.row.status==='active'?'muted':'active'})">{{ scope.row.status==='active'?'禁言':'解除' }}</el-button><el-button size="small" @click="adjust(scope.row)">积分</el-button><el-button size="small" @click="openRoleForm(scope.row)">角色与板块</el-button></template></el-table-column></el-table></template>
  <template v-if="section==='boards'"><div class="section-heading"><h2>板块</h2><el-button @click="openBoardForm()">新增板块</el-button></div>
    <form v-if="boardFormOpen" class="admin-form" @submit.prevent="saveBoard"><h3>{{ boardForm.id?'编辑板块':'新增板块' }}</h3><div class="form-row"><label>名称<input v-model="boardForm.name" maxlength="50" required placeholder="2 到 50 个字"/></label><label>标识<input v-model="boardForm.slug" maxlength="80" required placeholder="小写字母、数字或短横线"/></label></div><div class="form-row"><label>简介<input v-model="boardForm.description" maxlength="255" placeholder="板块简介"/></label><label>排序<input v-model.number="boardForm.sortOrder" type="number" min="0" max="9999"/></label></div><div class="v2-inline"><button class="button primary" type="submit">保存</button><button class="button" type="button" @click="boardFormOpen=false">取消</button></div></form>
    <div class="admin-list"><div v-for="item in boards" :key="item.id"><span><strong>{{ item.name }} <small v-if="item.status!=='enabled'">已停用</small></strong><small>{{ item.slug }} · 排序 {{ item.sort_order }} · {{ item.description || '暂无简介' }}</small></span><span class="admin-row-actions"><button class="table-action" @click="openBoardForm(item)">编辑</button><button class="table-action" :class="{danger:item.status==='enabled'}" @click="toggleBoard(item)">{{ item.status==='enabled'?'停用':'恢复' }}</button></span></div></div>
    <nav v-if="!loading && (hasNext || page>1)" class="pager" aria-label="板块分页"><button :disabled="page===1" @click="changePage(page-1)">上一页</button><span>板块第 {{ page }} 页</span><button :disabled="!hasNext" @click="changePage(page+1)">下一页</button></nav>
    <div class="section-heading"><h2>标签</h2><el-button @click="openTagForm()">新增标签</el-button></div>
    <form v-if="tagFormOpen" class="admin-form" @submit.prevent="saveTag"><h3>{{ tagForm.id?'编辑标签':'新增标签' }}</h3><label>名称<input v-model="tagForm.name" maxlength="40" required placeholder="2 到 40 个字"/></label><div class="v2-inline"><button class="button primary" type="submit">保存</button><button class="button" type="button" @click="tagFormOpen=false">取消</button></div></form>
    <div class="admin-list"><div v-for="item in tags" :key="item.id"><span><strong># {{ item.name }} <small v-if="item.status!=='enabled'">已停用</small></strong></span><span class="admin-row-actions"><button class="table-action" @click="openTagForm(item)">编辑</button><button class="table-action" :class="{danger:item.status==='enabled'}" @click="toggleTag(item)">{{ item.status==='enabled'?'停用':'恢复' }}</button></span></div></div></template>
  <nav v-if="section==='boards' && !loading && (tagHasNext || tagPage>1)" class="pager" aria-label="标签分页"><button :disabled="tagPage===1" @click="changeTagPage(tagPage-1)">上一页</button><span>标签第 {{ tagPage }} 页</span><button :disabled="!tagHasNext" @click="changeTagPage(tagPage+1)">下一页</button></nav>
  <template v-if="section==='points'"><h2>管理操作记录</h2><div class="admin-list"><div v-for="item in logs" :key="item.id"><strong>{{ item.action }}</strong><span>{{ item.target_type }} #{{ item.target_id }} · {{ item.detail }}</span></div></div></template>
  <template v-if="section==='appeals'"><h2>申诉管理</h2><div class="account-list"><article v-for="item in appeals" :key="item.id" class="v2-record"><strong>{{ item.username }} · {{ item.target_type }} #{{ item.target_id }} · {{ item.status }}</strong><p>{{ item.reason }}</p><small v-if="item.handle_note">处理备注：{{ item.handle_note }}</small><div v-if="item.status==='pending'"><button v-if="appealHandlingId!==item.id" class="button" @click="appealHandlingId=item.id; appealNote=''; error=''">填写处理意见</button><div v-else class="appeal-review"><label :for="'appeal-note-'+item.id">处理备注</label><textarea :id="'appeal-note-'+item.id" v-model="appealNote" maxlength="500" rows="3" placeholder="请填写处理结果与说明" :disabled="appealSubmitting"/><div class="v2-inline"><button class="button primary" :disabled="!appealNote.trim() || appealSubmitting" @click="handleAppeal(item,'resolved')">处理</button><button class="button" :disabled="!appealNote.trim() || appealSubmitting" @click="handleAppeal(item,'rejected')">驳回</button><button class="button" :disabled="appealSubmitting" @click="appealHandlingId=undefined; appealNote=''">取消</button></div></div></div></article></div></template>
  <template v-if="section==='reputation'"><button class="button primary" @click="adjustReputation">调整信誉分</button><div class="account-list"><article v-for="item in reputationLogs" :key="item.id" class="v2-record">{{ item.username }} · {{ item.amount>0?'+':'' }}{{ item.amount }} · 余额 {{ item.balance_after }} · {{ item.reason }}</article></div></template>
  <template v-if="section==='announcements'"><h2>发布系统公告</h2><textarea v-model="announcement" rows="5" maxlength="255" placeholder="公告内容至少 5 个字，最多 255 个字"/><div class="announcement-meta"><span :class="{over:announcement.length>255}">{{ announcement.length }}/255</span><button class="button primary" :disabled="announcement.trim().length<5 || announcement.length>255" @click="publishAnnouncement">发布公告</button></div>
    <form v-if="announcementEditing" class="admin-form" @submit.prevent="saveAnnouncementEdit"><h3>编辑第 {{ announcementEditing.id }} 条公告</h3><label>公告内容<textarea v-model="announcementDraft" rows="4" maxlength="255" required/></label><p class="admin-form-hint">只更新正文，已经读过这条公告的用户不会再被弹窗提醒一次。</p><div class="v2-inline"><button class="button primary" type="submit">保存</button><button class="button" type="button" @click="cancelEditAnnouncement">取消</button></div></form>
    <div class="section-heading"><h2>已发布的公告</h2><span>本页 {{ announcements.length }} 条{{ hasNext ? '，还有下一页' : '' }}</span></div>
    <div class="admin-list"><div v-for="item in announcements" :key="item.id"><span class="announcement-text"><strong>{{ item.message }}</strong><small>{{ item.admin_name }} · {{ date(item.created_at) }}<template v-if="item.updated_at"> · 已编辑</template> · 已读 {{ item.read_count }}/{{ item.recipients }}</small></span><span class="admin-row-actions"><button class="table-action" @click="startEditAnnouncement(item)">编辑</button><button class="table-action danger" @click="announcementToRemove=item">删除</button></span></div></div>
    <p v-if="!loading && !announcements.length" class="admin-form-hint">还没有发布过公告。</p></template>
  <template v-if="section==='checkin'">
    <p class="admin-form-hint">打卡积分与规则会立即对所有用户生效；关闭补卡后，用户只能当天打卡。</p>
    <form class="admin-form" @submit.prevent="saveSettings"><h3>打卡与积分规则</h3>
      <div class="form-row"><label>每次打卡积分<input v-model.number="settingsForm.checkin_points" type="number" min="0" max="100"/></label><label>连续奖励积分<input v-model.number="settingsForm.streak_bonus" type="number" min="0" max="100"/></label><label>连续多少天给奖励<input v-model.number="settingsForm.streak_bonus_days" type="number" min="1" max="365"/></label></div>
      <div class="form-row"><label>番茄钟每分钟积分<input v-model.number="settingsForm.focus_points" type="number" min="0" max="100"/></label><label>专注每日积分上限<input v-model.number="settingsForm.focus_daily_cap" type="number" min="0" max="1440"/></label><label>完成计划任务积分<input v-model.number="settingsForm.plan_task_points" type="number" min="0" max="100"/></label></div>
      <div class="form-row"><label>任务积分每日上限<input v-model.number="settingsForm.plan_task_daily_cap" type="number" min="0" max="1440"/></label><label>完成整个计划积分<input v-model.number="settingsForm.plan_complete_points" type="number" min="0" max="500"/></label><label class="choice"><input v-model="settingsForm.allow_makeup" type="checkbox"/>允许补卡（近 2 天内）</label></div>
      <button class="button primary" type="submit" :disabled="checkingSettings">{{ checkingSettings?'保存中…':'保存规则' }}</button>
    </form>
    <h2>打卡数据概览</h2>
    <div class="stats-grid"><div v-for="item in [['今日打卡',checkinData.today?.checkins],['今日打卡人数',checkinData.today?.users],['今日时长（分钟）',checkinData.today?.minutes],['累计打卡',checkinData.total?.checkins],['累计打卡人数',checkinData.total?.users],['累计时长（分钟）',checkinData.total?.minutes],['近 30 天补卡',checkinData.makeup?.count],['打卡目标',checkinData.goals?.total]]" :key="item[0]"><strong>{{ item[1] ?? '—' }}</strong><span>{{ item[0] }}</span></div></div>
    <div class="section-heading"><h2>近 14 天打卡趋势</h2><span>按天统计的打卡次数与参与人数</span></div>
    <div class="admin-list"><div v-for="item in checkinData.trend || []" :key="item.date"><strong>{{ date(item.date) }}</strong><span>{{ item.count }} 次打卡 · {{ item.users }} 人参与</span></div><p v-if="!(checkinData.trend || []).length" class="admin-form-hint">近 14 天还没有打卡记录。</p></div>
    <div class="section-heading"><h2>学科分布</h2><span>近 30 天</span></div>
    <div class="admin-list"><div v-for="item in checkinData.subjects || []" :key="item.subject"><strong>{{ item.subject }}</strong><span>{{ item.count }} 次 · {{ item.minutes }} 分钟</span></div><p v-if="!(checkinData.subjects || []).length" class="admin-form-hint">暂无学科数据。</p></div>
    <div class="section-heading"><h2>徽章获得情况</h2><span>共 {{ (checkinData.badges || []).length }} 种徽章被获得</span></div>
    <div class="admin-list"><div v-for="item in checkinData.badges || []" :key="item.code"><strong>{{ item.name }}</strong><span>{{ item.count }} 人获得 · {{ item.code }}</span></div><p v-if="!(checkinData.badges || []).length" class="admin-form-hint">还没有用户获得徽章。</p></div>
    <h2>最近打卡记录</h2>
    <el-table :data="checkinData.recent || []" stripe><el-table-column prop="username" label="用户" width="110"/><el-table-column prop="goal_title" label="目标" min-width="140"/><el-table-column prop="content" label="内容" min-width="200"/><el-table-column prop="subject" label="学科" width="90"/><el-table-column prop="duration_minutes" label="分钟" width="80"/><el-table-column label="补卡" width="70"><template #default="scope">{{ scope.row.make_up?'是':'否' }}</template></el-table-column><el-table-column label="日期" width="110"><template #default="scope">{{ date(scope.row.checkin_date) }}</template></el-table-column><el-table-column prop="points" label="积分" width="70"/></el-table>
  </template>
  <template v-if="section==='focus'">
    <p class="admin-form-hint">专注数据来自番茄钟、计划联动与自习室记录；异常时长指单次超过 240 分钟或不足 1 分钟的记录。</p>
    <div class="v2-inline"><button class="button" :class="{primary:focusRange==='week'}" @click="focusRange='week'">本周</button><button class="button" :class="{primary:focusRange==='month'}" @click="focusRange='month'">近 30 天</button><button class="button" :class="{primary:focusRange==='year'}" @click="focusRange='year'">近一年</button></div>
    <div class="stats-grid"><div v-for="item in [['专注次数',focusData.summary?.sessions],['参与人数',focusData.summary?.users],['专注总时长（分钟）',focusData.summary?.minutes],['平均每次（分钟）',focusData.summary?.average],['今日次数',focusData.today?.sessions],['今日时长（分钟）',focusData.today?.minutes],['异常记录',(focusData.suspicious || []).length]]" :key="item[0]"><strong>{{ item[1] ?? '—' }}</strong><span>{{ item[0] }}</span></div></div>
    <div class="section-heading"><h2>专注趋势</h2><span>{{ focusRange==='week'?'近 7 天':focusRange==='month'?'近 30 天':'近一年' }}</span></div>
    <div class="admin-list"><div v-for="item in focusData.trend || []" :key="item.date"><strong>{{ date(item.date) }}</strong><span>{{ item.sessions }} 次 · {{ item.minutes }} 分钟 · {{ item.users }} 人</span></div><p v-if="!(focusData.trend || []).length" class="admin-form-hint">该区间没有专注记录。</p></div>
    <div class="section-heading"><h2>学科与来源</h2></div>
    <div class="admin-list"><div v-for="item in focusData.subjects || []" :key="item.subject"><strong>{{ item.subject }}</strong><span>{{ item.sessions }} 次 · {{ item.minutes }} 分钟</span></div><p v-if="!(focusData.subjects || []).length" class="admin-form-hint">暂无学科数据。</p></div>
    <div class="admin-list"><div v-for="item in focusData.sources || []" :key="item.source"><strong>{{ sourceLabel[item.source] || item.source }}</strong><span>{{ item.sessions }} 次 · {{ item.minutes }} 分钟</span></div></div>
    <div class="section-heading"><h2>专注排行</h2><span>前 20 名</span></div>
    <div class="admin-list"><div v-for="(item,index) in focusData.top || []" :key="item.user_id"><strong>#{{ index+1 }} {{ item.username }}</strong><span>{{ item.sessions }} 次 · {{ item.minutes }} 分钟</span></div><p v-if="!(focusData.top || []).length" class="admin-form-hint">该区间还没有排行数据。</p></div>
    <div class="section-heading"><h2>异常时长记录</h2><span>可删除误计时的记录</span></div>
    <el-table :data="focusData.suspicious || []" stripe><el-table-column prop="username" label="用户" width="110"/><el-table-column prop="subject" label="学科" width="100"/><el-table-column prop="duration_minutes" label="分钟" width="80"/><el-table-column label="来源" width="100"><template #default="scope">{{ sourceLabel[scope.row.source] || scope.row.source }}</template></el-table-column><el-table-column label="开始时间" min-width="160"><template #default="scope">{{ date(scope.row.started_at) }}</template></el-table-column><el-table-column label="操作" width="100"><template #default="scope"><el-button size="small" type="danger" @click="deleteSession(scope.row)">删除</el-button></template></el-table-column></el-table>
  </template>
  <template v-if="section==='study-rooms'">
    <p class="admin-form-hint">关闭自习室会保留成员与留言以便复核，删除则不可恢复。</p>
    <div class="v2-inline"><button class="button" :class="{primary:roomStatus==='active'}" @click="roomStatus='active'">进行中</button><button class="button" :class="{primary:roomStatus==='closed'}" @click="roomStatus='closed'">已关闭</button><button class="button" :class="{primary:roomStatus==='all'}" @click="roomStatus='all'">全部</button></div>
    <div class="stats-grid"><div v-for="item in [['自习室总数',roomData.summary?.total],['进行中',roomData.summary?.active],['已关闭',roomData.summary?.closed],['私密房间',roomData.summary?.private_rooms],['累计专注（分钟）',roomData.summary?.focus_minutes],['近 24 小时活跃成员',roomData.active?.members]]" :key="item[0]"><strong>{{ item[1] ?? '—' }}</strong><span>{{ item[0] }}</span></div></div>
    <div class="section-heading"><h2>专注时长榜</h2><span>房间累计专注前 10</span></div>
    <div class="admin-list"><div v-for="(item,index) in roomData.leaders || []" :key="item.id"><strong>#{{ index+1 }} {{ item.name }}</strong><span>{{ item.focus_minutes }} 分钟 · 房主 {{ item.owner_username }}</span></div><p v-if="!(roomData.leaders || []).length" class="admin-form-hint">还没有自习室数据。</p></div>
    <h2>自习室列表</h2>
    <el-table :data="roomData.items || []" stripe><el-table-column prop="name" label="名称" min-width="150"/><el-table-column prop="owner_username" label="房主" width="110"/><el-table-column prop="subject" label="学科" width="90"/><el-table-column label="可见性" width="90"><template #default="scope">{{ scope.row.visibility==='public'?'公开':'私密' }}</template></el-table-column><el-table-column label="状态" width="90"><template #default="scope">{{ scope.row.status==='active'?'进行中':'已关闭' }}</template></el-table-column><el-table-column prop="members" label="成员" width="70"/><el-table-column prop="messages" label="留言" width="70"/><el-table-column prop="focus_minutes" label="专注分钟" width="90"/><el-table-column label="静音" width="70"><template #default="scope">{{ scope.row.muted?'是':'否' }}</template></el-table-column><el-table-column label="操作" width="180"><template #default="scope"><el-button size="small" @click="change('/admin/study-tools/study-rooms/'+scope.row.id,'PATCH',{status:scope.row.status==='active'?'closed':'active'})">{{ scope.row.status==='active'?'关闭':'恢复' }}</el-button><el-button size="small" @click="change('/admin/study-tools/study-rooms/'+scope.row.id,'PATCH',{muted:!scope.row.muted})">{{ scope.row.muted?'解除静音':'静音' }}</el-button><el-button size="small" type="danger" @click="deleteRoom(scope.row)">删除</el-button></template></el-table-column></el-table>
    <p v-if="!loading && !(roomData.items || []).length" class="admin-form-hint">没有符合条件的自习室。</p>
  </template>
  <nav v-if="(searchHint || section==='announcements') && section!=='boards' && !loading && (hasNext || page>1)" class="pager" aria-label="后台分页"><button :disabled="page===1" @click="changePage(page-1)">上一页</button><span>第 {{ page }} 页</span><button :disabled="!hasNext" @click="changePage(page+1)">下一页</button></nav>
  <ConfirmDialog :open="Boolean(announcementToRemove)" title="删除这条公告" :subject="announcementToRemove?.message" description="删除后所有用户的通知列表里都会移除这条公告，且无法恢复。" confirm-text="确认删除" pending-text="删除中…" :pending="announcementDeleting" @confirm="confirmRemoveAnnouncement" @cancel="announcementToRemove=null"/>
  </div><div v-else class="empty">此页面仅供版主和管理员使用。</div></template>
