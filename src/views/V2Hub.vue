<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, date, send, upload } from '../api'
import { useSession } from '../store'
type AppealTarget = { target_type: string; target_id: number; label: string }
const appealTypeNames: Record<string, string> = { post: '帖子', reply: '回复', user: '账号', report: '举报', message: '私信' }
const appealStatusNames: Record<string, string> = { pending: '待处理', resolved: '已处理', rejected: '已驳回' }
const route=useRoute(), router=useRouter(), session=useSession(), section=computed(() => route.path), error=ref(''), notice=ref('')
const items=ref<any[]>([]), detail=ref<any[]>([]), draft=ref<any>(), text=ref(''), target=ref(''), image=ref(''), settings=ref({messagePrivacy:'following',notifyUnfollow:false,notifyMention:true}), timer=ref<ReturnType<typeof setInterval>>(), muted=ref(false), otherId=ref<number>(), tags=ref<any[]>([]), followedTags=ref<any[]>([])
const appealTargets=ref<AppealTarget[]>([]), appealTarget=ref(''), appealReason=ref(''), appealOpen=ref(false), appealSubmitting=ref(false)
const conversationId=computed(() => Number(route.params.conversationId)), postId=computed(() => Number(route.params.id))
async function load() { error.value=''; try {
  if (section.value==='/feed/following') { [items.value,tags.value,followedTags.value]=await Promise.all([api('/feed/following'),api('/tags'),api('/me/tags/following')]) }
  else if (section.value==='/messages') items.value=await api('/conversations')
  else if (section.value.startsWith('/messages/')) { detail.value=await api('/conversations/'+conversationId.value+'/messages'); const conversation=(await api<any[]>('/conversations')).find(item=>item.id===conversationId.value); muted.value=Boolean(conversation?.muted); otherId.value=conversation?.other_id }
  else if (section.value==='/me/drafts') items.value=await api('/me/drafts')
  else if (section.value==='/appeals') [items.value,appealTargets.value]=await Promise.all([api('/me/appeals'),api('/me/appeal-targets')])
  else if (section.value.includes('/revisions')) items.value=await api('/posts/'+postId.value+'/revisions')
  else if (section.value.endsWith('/settings') && session.user) settings.value={messagePrivacy:session.user.message_privacy || 'following',notifyUnfollow:session.user.notify_unfollow,notifyMention:session.user.notify_mention}
} catch (exception) { error.value=(exception as Error).message } }
onMounted(() => { load(); timer.value=setInterval(() => { if (section.value.startsWith('/messages/')) load() },2000) })
onUnmounted(() => { if (timer.value) clearInterval(timer.value) })
watch(section,load)
async function run(task:() => Promise<unknown>) { try { error.value=''; await task(); await load() } catch (exception) { error.value=(exception as Error).message } }
async function start() { await run(async () => { const result=await send<{id:number}>('/conversations','POST',{userId:Number(target.value)}); router.push('/messages/'+result.id) }) }
async function message() { await run(async () => { await send('/conversations/'+conversationId.value+'/messages','POST',{type:image.value?'image':'text',content:image.value || text.value}); text.value=''; image.value='' }) }
async function addImage(event:Event) { const file=(event.target as HTMLInputElement).files?.[0]; if (file) try { image.value=(await upload(file)).path } catch (exception) { error.value=(exception as Error).message } }
async function newDraft() { await run(async () => { const result=await send<{id:number}>('/drafts','POST',{type:'question',title:'',content:'',tagIds:''}); router.push('/posts/new?draft='+result.id) }) }
async function appeal() {
  const selected=appealTargets.value.find(item=>`${item.target_type}:${item.target_id}`===appealTarget.value)
  const reason=appealReason.value.trim()
  if (!selected || reason.length<10) { error.value='请选择申诉对象并填写至少 10 个字的理由'; return }
  appealSubmitting.value=true
  error.value=''
  notice.value=''
  try {
    await send('/appeals','POST',{targetType:selected.target_type,targetId:selected.target_id,reason})
    appealOpen.value=false
    appealTarget.value=''
    appealReason.value=''
    await load()
    notice.value='申诉已提交，请等待处理'
  } catch (exception) { error.value=(exception as Error).message }
  finally { appealSubmitting.value=false }
}
async function revision(id:number) { await run(async () => { const current=await api<any>('/posts/'+postId.value); const previous=await api<any>('/posts/'+postId.value+'/revisions/'+id); detail.value=[{...previous,diff:lineDiff(previous.content,current.content)}] }) }
function lineDiff(previous:string,current:string) { const before=previous.split('\n'), after=current.split('\n'); return [...before.filter(line=>!after.includes(line)).map(line=>'- '+line),...after.filter(line=>!before.includes(line)).map(line=>'+ '+line)] }
async function toggleTag(id:number) { await run(() => send('/me/tags/'+id+'/follow',followedTags.value.some(tag=>tag.id===id)?'DELETE':'POST')) }
async function toggleMute() { await run(() => send('/conversations/'+conversationId.value+'/mute','PATCH',{muted:!muted.value})) }
async function blockUser() { if (otherId.value && window.confirm('拉黑后双方将无法继续私信，确定吗？')) await run(() => send('/users/'+otherId.value+'/block','POST')) }
async function saveSettings() { await run(async () => { await send('/me/notifications/settings','PUT',settings.value); notice.value='设置已保存'; await session.refresh() }) }
</script>
<template><div class="content-page v2-page"><div class="page-heading"><span>持续学习，保持连接</span><h1>{{ section==='/feed/following'?'关注动态':section.startsWith('/messages')?'私信':section==='/me/drafts'?'草稿箱':section==='/appeals'?'申诉记录':section.includes('/revisions')?'编辑历史':'通知与隐私' }}</h1><p>在同频社区中，认真交流的每一步都有迹可循。</p></div><p v-if="error" class="error">{{ error }}</p><p v-if="notice" class="success">{{ notice }}</p>
  <template v-if="section==='/feed/following'"><div class="v2-tags"><button v-for="tag in tags" :key="tag.id" :class="{selected:followedTags.some(item=>item.id===tag.id)}" @click="toggleTag(tag.id)"># {{ tag.name }} {{ followedTags.some(item=>item.id===tag.id)?'已关注':'关注' }}</button></div><div v-if="!items.length" class="empty">还没有关注动态，先去认识一位同学吧。</div><div class="account-list"><RouterLink v-for="item in items" :key="item.kind+item.post_id+item.created_at" :to="'/posts/'+item.post_id"><strong>{{ item.username }} · {{ {post:'发帖',reply:'回复',accepted:'采纳',featured:'加精',tag:'话题新帖'}[item.kind as 'post'] }}</strong><span>{{ item.summary }} · {{ date(item.created_at) }}</span></RouterLink></div></template>
  <template v-else-if="section==='/messages'"><form class="v2-inline" @submit.prevent="start"><input v-model="target" type="number" min="1" required placeholder="用户 ID"/><button class="button primary">发起私信</button></form><div v-if="!items.length" class="empty">暂无会话。</div><div class="account-list"><RouterLink v-for="item in items" :key="item.id" :to="'/messages/'+item.id"><strong>{{ item.username }} <span v-if="item.unread">· {{ item.unread }} 条未读</span></strong><span>{{ item.last_message || '开始聊天' }}</span></RouterLink></div></template>
  <template v-else-if="section.startsWith('/messages/')"><RouterLink to="/messages">← 返回会话</RouterLink><div class="v2-chat"><article v-for="item in detail" :key="item.id" :class="{mine:item.sender_id===session.user?.id}"><span>{{ item.sender_id===session.user?.id?'我':'对方' }} · {{ date(item.created_at) }}</span><img v-if="item.type==='image'" :src="item.content" alt="私信图片"/><p v-else>{{ item.content }}</p><button v-if="item.sender_id!==session.user?.id" @click="run(() => send('/messages/'+item.id+'/report','POST',{reason:'其他',description:'私信举报'}))">举报</button></article></div><form class="v2-inline" @submit.prevent="message"><input v-model="text" :required="!image" maxlength="2000" placeholder="发送文字或 @ 同学"/><label>图片<input type="file" accept="image/*" @change="addImage"/></label><span v-if="image">已选图片</span><button class="button primary">发送</button></form><button @click="toggleMute">{{ muted?'关闭免打扰':'开启免打扰' }}</button><button @click="blockUser">拉黑对方</button></template>
  <template v-else-if="section==='/me/drafts'"><button class="button primary" @click="newDraft">新建草稿</button><div class="account-list"><div v-for="item in items" :key="item.id" class="v2-draft"><RouterLink :to="'/posts/new?draft='+item.id"><strong>{{ item.title || '未命名草稿' }}</strong><span>{{ date(item.updated_at) }}</span></RouterLink><button @click="run(() => send('/drafts/'+item.id,'DELETE'))">删除</button></div></div></template>
  <template v-else-if="section==='/appeals'"><button v-if="!appealOpen" class="button primary" @click="appealOpen=true; notice=''">发起申诉</button><form v-else class="compose-form appeal-form" @submit.prevent="appeal"><h2>提交申诉</h2><p>请选择被处理的本人内容或账号，无需查找对象 ID。已有待处理申诉的对象不会重复显示。</p><label>申诉对象<select v-model="appealTarget" required :disabled="appealSubmitting"><option value="" disabled>请选择申诉对象</option><option v-for="item in appealTargets" :key="item.target_type+':'+item.target_id" :value="item.target_type+':'+item.target_id">{{ appealTypeNames[item.target_type] || item.target_type }} · {{ item.label }} (#{{ item.target_id }})</option></select></label><p v-if="!appealTargets.length" class="appeal-hint">目前没有可申诉的对象。</p><label>申诉理由<textarea v-model="appealReason" required minlength="10" maxlength="2000" rows="5" :disabled="appealSubmitting" placeholder="请说明处理经过及申诉理由（至少 10 个字）"/></label><div class="v2-inline"><button class="button primary" type="submit" :disabled="!appealTarget || appealReason.trim().length<10 || appealSubmitting">{{ appealSubmitting?'提交中…':'提交申诉' }}</button><button class="button" type="button" :disabled="appealSubmitting" @click="appealOpen=false; error=''">取消</button></div></form><h2 class="appeal-list-title">申诉记录</h2><div v-if="!items.length" class="empty">暂无申诉记录。</div><div v-else class="account-list"><article v-for="item in items" :key="item.id" class="v2-record"><strong>#{{ item.id }} · {{ appealTypeNames[item.target_type] || item.target_type }} #{{ item.target_id }} · {{ appealStatusNames[item.status] || item.status }}</strong><p>{{ item.reason }}</p><small v-if="item.handle_note">处理备注：{{ item.handle_note }}</small></article></div></template>
  <template v-else-if="section.includes('/revisions')"><div class="account-list"><button v-for="item in items" :key="item.id" @click="revision(item.id)">#{{ item.id }} · {{ item.title }} · {{ date(item.created_at) }}</button></div><article v-if="detail[0]" class="v2-record"><h2>{{ detail[0].title }}</h2><p>{{ detail[0].content }}</p><h3>与当前版本对比</h3><pre class="v2-diff">{{ detail[0].diff.join('\n') || '正文没有变化' }}</pre></article></template>
  <form v-else class="compose-form" @submit.prevent="saveSettings"><label>私信权限<select v-model="settings.messagePrivacy"><option value="following">仅我关注的人</option><option value="everyone">所有人</option><option value="closed">关闭私信</option></select></label><label class="check"><input v-model="settings.notifyUnfollow" type="checkbox"/> 取关通知</label><label class="check"><input v-model="settings.notifyMention" type="checkbox"/> @提及通知</label><button class="button primary">保存设置</button></form>
</div></template>
