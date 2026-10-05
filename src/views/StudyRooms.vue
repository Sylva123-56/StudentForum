<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, date, send } from '../api'
import { useSession } from '../store'
import { ElMessage } from 'element-plus'
import { LogOut, Pause, Play, Trash2 } from 'lucide-vue-next'
import ConfirmDialog from './ConfirmDialog.vue'

const route = useRoute(), router = useRouter(), session = useSession()
const error = ref(''), notice = ref(''), loading = ref(false)
const isList = computed(() => route.path === '/study-rooms')
const roomId = computed(() => Number(route.params.id))
const me = computed(() => session.user?.id ?? 0)

const rooms = ref<any[]>([]), mineRooms = ref<any[]>([]), groupOptions = ref<any[]>([])
const scope = ref<'all' | 'host' | 'joined'>('all')
const subjectFilter = ref(''), showClosed = ref(false)
const showCreate = ref(false), creating = ref(false)
const form = ref({ name: '', goal: '', subject: '', visibility: 'public', minMinutes: 25, muted: false, groupId: 0 })

const detail = ref<any>(), showSettings = ref(false)
const settingsForm = ref({ name: '', goal: '', subject: '', visibility: 'public', minMinutes: 0, muted: false, status: 'active' })
const messages = ref<any[]>([]), input = ref(''), sending = ref(false)

const timerPhase = ref<'idle' | 'focus' | 'paused'>('idle')
const timerRemaining = ref(0), timerPlanned = ref(0), timerEndsAt = ref(0)
const focusForm = ref({ minutes: 25, task: '', subject: '', note: '' })

const pendingDialog = ref<'leave' | 'dissolve' | 'message' | null>(null)
const dialogPending = ref(false), messageToDelete = ref<any>(null)

let tickId: number | undefined, pollId: number | undefined, noticeTimer: number | undefined
let focusFormRoomId = 0

// ---------- 列表页派生的状态 ----------
const mineIndex = computed(() => { const map = new Map<number, any>(); for (const item of mineRooms.value) map.set(Number(item.id), item); return map })
const subjectOptions = computed(() => [...new Set(rooms.value.map((room: any) => String(room.subject || '').trim()).filter(Boolean))])
const visibleRooms = computed(() => rooms.value.filter((room: any) => !subjectFilter.value || String(room.subject || '').toLowerCase().includes(subjectFilter.value.trim().toLowerCase())))
const closedRooms = computed(() => mineRooms.value.filter((item: any) => item.status === 'closed' && (!subjectFilter.value.trim() || String(item.name || '').toLowerCase().includes(subjectFilter.value.trim().toLowerCase()))))
function isJoined(room: any) { return mineIndex.value.has(Number(room.id)) }
function myRoleIn(room: any) { if (!session.user) return ''; if (Number(room.owner_id) === me.value) return '房主'; return isJoined(room) ? '成员' : '' }
function groupTagName(groupId: any) { if (!groupId) return ''; const found = groupOptions.value.find((group: any) => Number(group.id) === Number(groupId)); return found ? String(found.name) : '已关联学习小组' }

// ---------- 房间页派生的状态 ----------
const room = computed<any>(() => detail.value || {})
const joined = computed(() => !!detail.value?.joined)
const isOwner = computed(() => !!me.value && Number(room.value.owner_id) === me.value)
const board = computed<any[]>(() => detail.value?.leaderboard || [])
const onlineCount = computed(() => (detail.value?.online || []).length)
const onlineNames = computed(() => (detail.value?.online || []).map((member: any) => member.username))
const onlineLabel = computed(() => onlineNames.value.slice(0, 8).join('、') + (onlineNames.value.length > 8 ? ' 等' : ''))
const memberCount = computed(() => Number(room.value.member_count || board.value.length))
const myEntry = computed(() => board.value.find((entry: any) => Number(entry.user_id) === me.value))
const myRole = computed(() => myEntry.value ? (myEntry.value.role === 'owner' ? '房主' : '成员') : (isOwner.value ? '房主' : ''))
const groupName = computed(() => detail.value?.groupName || '')
const canSpeak = computed(() => joined.value && (!room.value.muted || isOwner.value))
const speakHint = computed(() => !joined.value ? '请先加入自习室再发言' : (room.value.muted && !isOwner.value ? '房间已开启静音，只有房主可以发言' : ''))
const threshold = computed(() => Number(room.value.min_minutes) > 0 ? Number(room.value.min_minutes) : 25)
const clock = computed(() => { const total = Math.max(0, Math.ceil(timerRemaining.value / 1000)); return String(Math.floor(total / 60)).padStart(2, '0') + ':' + String(total % 60).padStart(2, '0') })
const timerActive = computed(() => timerPhase.value !== 'idle')
const plannedMinutes = computed(() => Math.round(timerPlanned.value / 60000))

const confirmDialog = computed(() => {
  if (pendingDialog.value === 'dissolve') return { icon: 'dissolve' as const, title: '解散这间自习室', subject: room.value.name, description: '解散后房间的留言与专注记录都不再展示，所有成员会被移出，且无法恢复。', confirmText: '确认解散', pendingText: '解散中…' }
  if (pendingDialog.value === 'message') return { icon: 'message' as const, title: '删除这条发言', subject: messageToDelete.value?.content, description: '删除后其他成员将看不到这条消息，且无法恢复。', confirmText: '确认删除', pendingText: '删除中…' }
  return { icon: 'leave' as const, title: '退出这间自习室', subject: room.value.name, description: '退出后你的专注时长仍会保留，但需要重新加入才能参与房间排行榜和留言。', confirmText: '确认退出', pendingText: '退出中…' }
})

function flash(message: string) {
  notice.value = message
  if (noticeTimer) clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => { notice.value = '' }, 3000)
}

async function load() {
  loading.value = true; error.value = ''
  try {
    if (isList.value) {
      const data = await api<any>('/study-rooms?' + new URLSearchParams({ scope: scope.value }))
      rooms.value = data.items || []
      mineRooms.value = data.mine || []
      groupOptions.value = data.groups || []
      return
    }
    const data = await api<any>('/study-rooms/' + roomId.value)
    detail.value = data
    messages.value = (data.messages || []).slice().reverse()
    if (showSettings.value && isOwner.value) fillSettings(data)
    if (!isOwner.value) showSettings.value = false
    if (timerPhase.value === 'idle' && focusFormRoomId !== Number(data.id)) {
      focusFormRoomId = Number(data.id)
      const minutes = Number(data.min_minutes) > 0 ? Number(data.min_minutes) : 25
      focusForm.value = { minutes, task: data.goal || '', subject: data.subject || '', note: '' }
    }
  } catch (e) { error.value = (e as Error).message } finally { loading.value = false }
}
function run(task: () => Promise<unknown>) { return task().then(() => load()).catch(e => { error.value = (e as Error).message }) }

// ---------- 广场 ----------
function openCreate() {
  if (!session.user) { router.push('/login'); return }
  showCreate.value = !showCreate.value
}
async function createRoom() {
  if (creating.value) return
  creating.value = true
  try {
    error.value = ''
    const created = await send<any>('/study-rooms', 'POST', {
      name: form.value.name, goal: form.value.goal, subject: form.value.subject, visibility: form.value.visibility,
      minMinutes: Number(form.value.minMinutes) || 0, groupId: Number(form.value.groupId) || null, muted: form.value.muted
    })
    flash('自习室创建成功')
    await router.push('/study-rooms/' + created.id)
  } catch (e) { error.value = (e as Error).message } finally { creating.value = false }
}
async function joinAndEnter(item: any) {
  if (!session.user) { await router.push('/login'); return }
  try {
    error.value = ''
    await send('/study-rooms/' + item.id + '/join', 'POST')
    ElMessage({ type: 'success', message: '已加入自习室' })
    await router.push('/study-rooms/' + item.id)
  } catch (e) { error.value = (e as Error).message }
}

// ---------- 房间操作 ----------
function joinRoom() { return run(async () => { await send('/study-rooms/' + roomId.value + '/join', 'POST'); ElMessage({ type: 'success', message: '已加入自习室' }) }) }
function askLeave() { pendingDialog.value = 'leave' }
function askDissolve() { pendingDialog.value = 'dissolve' }
function askDeleteMessage(message: any) { messageToDelete.value = message; pendingDialog.value = 'message' }
function canDeleteMessage(message: any) { return Number(message.user_id) === me.value || isOwner.value }
async function runPendingDialog() {
  const action = pendingDialog.value
  if (!action || dialogPending.value) return
  dialogPending.value = true
  try {
    error.value = ''
    if (action === 'leave') {
      await send('/study-rooms/' + roomId.value + '/leave', 'POST')
      ElMessage({ type: 'success', message: '已退出自习室' })
      await router.push('/study-rooms')
    } else if (action === 'dissolve') {
      await send('/study-rooms/' + roomId.value, 'DELETE')
      ElMessage({ type: 'success', message: '自习室已解散' })
      await router.push('/study-rooms')
    } else {
      await send('/study-rooms/' + roomId.value + '/messages/' + messageToDelete.value.id, 'DELETE')
      ElMessage({ type: 'success', message: '发言已删除' })
      await load()
    }
  } catch (e) { error.value = (e as Error).message } finally {
    dialogPending.value = false; pendingDialog.value = null; messageToDelete.value = null
  }
}
function fillSettings(row: any) {
  settingsForm.value = { name: row.name || '', goal: row.goal || '', subject: row.subject || '', visibility: row.visibility || 'public', minMinutes: Number(row.min_minutes) || 0, muted: !!row.muted, status: row.status || 'active' }
}
function openSettings() { fillSettings(room.value); showSettings.value = true }
function saveSettings() {
  return run(async () => {
    await send('/study-rooms/' + roomId.value, 'PATCH', {
      name: settingsForm.value.name, goal: settingsForm.value.goal, subject: settingsForm.value.subject,
      visibility: settingsForm.value.visibility, minMinutes: Number(settingsForm.value.minMinutes) || 0,
      muted: settingsForm.value.muted, status: settingsForm.value.status
    })
    flash('房间设置已保存')
  })
}

// ---------- 消息 ----------
async function postMessage() {
  const content = input.value.trim()
  if (!canSpeak.value || !content || sending.value) return
  sending.value = true
  try {
    error.value = ''
    const created = await send<any>('/study-rooms/' + roomId.value + '/messages', 'POST', { content })
    if (created && created.id) messages.value = [...messages.value, created]
    input.value = ''
  } catch (e) { error.value = (e as Error).message } finally { sending.value = false }
}

// ---------- 专注计时（绝对时间戳，setInterval 只负责刷新显示） ----------
function startTick() {
  stopTick()
  tickId = window.setInterval(() => {
    if (timerPhase.value !== 'focus') return
    const left = timerEndsAt.value - Date.now()
    timerRemaining.value = Math.max(0, left)
    if (left <= 0) finishTimer()
  }, 250)
}
function stopTick() { if (tickId !== undefined) { clearInterval(tickId); tickId = undefined } }
function startTimer() {
  if (!session.user) { router.push('/login'); return }
  const minutes = Math.min(180, Math.max(1, Math.round(Number(focusForm.value.minutes) || threshold.value)))
  focusForm.value.minutes = minutes
  timerPlanned.value = minutes * 60000
  timerRemaining.value = timerPlanned.value
  timerEndsAt.value = Date.now() + timerRemaining.value
  timerPhase.value = 'focus'
  startTick()
}
function pauseTimer() { if (timerPhase.value !== 'focus') return; timerRemaining.value = Math.max(0, timerEndsAt.value - Date.now()); timerPhase.value = 'paused' }
function resumeTimer() { if (timerPhase.value !== 'paused') return; timerEndsAt.value = Date.now() + timerRemaining.value; timerPhase.value = 'focus' }
function cancelTimer() { stopTick(); timerPhase.value = 'idle'; timerRemaining.value = 0; timerPlanned.value = 0; timerEndsAt.value = 0 }
function finishTimer() {
  if (timerPhase.value === 'idle') return
  if (timerPhase.value === 'focus') timerRemaining.value = Math.max(0, timerEndsAt.value - Date.now())
  stopTick()
  const elapsed = Math.max(0, timerPlanned.value - timerRemaining.value)
  const minutes = Math.min(720, Math.max(1, Math.round(elapsed / 60000)))
  cancelTimer()
  return run(async () => {
    const result = await send<any>('/focus/sessions', 'POST', {
      durationMinutes: minutes,
      subject: focusForm.value.subject || room.value.subject || '',
      task: focusForm.value.task || room.value.goal || '',
      note: focusForm.value.note || '',
      roomId: roomId.value,
      source: 'room'
    })
    ElMessage({ type: 'success', message: '已记录 ' + minutes + ' 分钟专注' })
    const badges = (result?.badges || []).map((badge: any) => typeof badge === 'string' ? badge : (badge?.name || badge?.code || '')).filter(Boolean)
    if (badges.length) ElMessage({ type: 'success', message: '获得新徽章：' + badges.join('、') })
  })
}

// ---------- 轮询与生命周期 ----------
async function poll() {
  if (isList.value || !roomId.value || pendingDialog.value || showSettings.value || timerActive.value) return
  try {
    const data = await api<any>('/study-rooms/' + roomId.value)
    detail.value = data
    messages.value = (data.messages || []).slice().reverse()
  } catch { /* 轮询失败静默忽略，避免打扰正在专注的人 */ }
}
watch(() => route.fullPath, () => { showSettings.value = false; cancelTimer(); pendingDialog.value = null; load() })
watch(scope, () => { if (isList.value) load() })
onMounted(() => { load(); pollId = window.setInterval(poll, 10000) })
onUnmounted(() => {
  stopTick()
  if (pollId !== undefined) clearInterval(pollId)
  if (noticeTimer) clearTimeout(noticeTimer)
})
</script>

<template>
  <section class="rooms-page">
    <div v-if="error" class="group-alert error">{{ error }}</div>
    <div v-if="notice" class="group-alert success">{{ notice }}</div>

    <template v-if="isList">
      <div class="room-heading">
        <div>
          <p class="eyebrow">FOCUS TOGETHER</p>
          <h1>自习室</h1>
          <p>线上一起专注，彼此陪伴。各自开着计时器努力，累了抬头看看还有人在坚持。</p>
        </div>
        <button class="button primary" @click="openCreate">{{ showCreate ? '收起创建表单' : '创建自习室' }}</button>
      </div>

      <div class="group-card room-filters">
        <div class="filter-chips">
          <button type="button" :class="['chip', scope === 'all' ? 'active' : '']" @click="scope = 'all'">全部</button>
          <template v-if="session.user">
            <button type="button" :class="['chip', scope === 'host' ? 'active' : '']" @click="scope = 'host'">我创建的</button>
            <button type="button" :class="['chip', scope === 'joined' ? 'active' : '']" @click="scope = 'joined'">我参与的</button>
          </template>
        </div>
        <div class="filter-row">
          <input v-model="subjectFilter" list="room-subjects" placeholder="按学科筛选，如：数学" />
          <datalist id="room-subjects">
            <option v-for="item in subjectOptions" :key="item" :value="item" />
          </datalist>
          <label v-if="session.user" class="choice"><input v-model="showClosed" type="checkbox" />显示我已关闭的自习室</label>
        </div>
      </div>

      <form v-if="showCreate" class="group-card room-form" @submit.prevent="createRoom">
        <label>自习室名称<input v-model="form.name" required minlength="2" maxlength="40" placeholder="例如：早八自律自习室" /></label>
        <label>学习目标<textarea v-model="form.goal" rows="3" maxlength="120" placeholder="这间自习室想一起完成什么"></textarea></label>
        <div class="form-two">
          <label>学科<input v-model="form.subject" maxlength="40" placeholder="数学 / 英语 / 编程" /></label>
          <label>最低专注时长（分钟，0 表示不限）<input v-model.number="form.minMinutes" type="number" min="0" max="720" /></label>
          <label>关联学习小组（可选）<select v-model="form.groupId">
              <option :value="0">不关联</option>
              <option v-for="group in groupOptions" :key="group.id" :value="group.id">{{ group.name }}</option>
            </select></label>
        </div>
        <fieldset>
          <legend>可见性</legend>
          <label class="choice"><input v-model="form.visibility" type="radio" value="public" />公开：所有人都能在自习室广场看到</label>
          <label class="choice"><input v-model="form.visibility" type="radio" value="private" />私密：只有加入的成员能进入</label>
        </fieldset>
        <label class="choice"><input v-model="form.muted" type="checkbox" />开启静音：只有房主可以发言</label>
        <div class="form-actions">
          <button class="button primary" type="submit" :disabled="creating">{{ creating ? '创建中…' : '创建自习室' }}</button>
          <button class="button ghost" type="button" @click="showCreate = false">取消</button>
        </div>
      </form>

      <div class="room-grid">
        <article v-for="item in visibleRooms" :key="item.id" class="group-card room-card">
          <div class="room-top">
            <RouterLink class="room-title" :to="'/study-rooms/' + item.id">{{ item.name }}</RouterLink>
            <span class="badge" :class="item.visibility">{{ item.visibility === 'public' ? '公开' : '私密' }}</span>
          </div>
          <p class="room-goal">{{ item.goal || '这间自习室还没有写学习目标。' }}</p>
          <div class="room-tags">
            <span v-if="item.subject">{{ item.subject }}</span>
            <span v-if="item.group_id">小组：{{ groupTagName(item.group_id) }}</span>
            <span v-if="myRoleIn(item)" class="role">{{ myRoleIn(item) }}</span>
            <span v-if="item.muted">已静音</span>
          </div>
          <div class="tile-meta">
            <span>共 {{ item.member_count || item.members }} 人</span>
            <span>累计专注 {{ item.focus_minutes }} 分钟</span>
            <span v-if="item.min_minutes > 0">需专注满 {{ item.min_minutes }} 分钟</span>
            <span>{{ item.owner_username }} 创建</span>
          </div>
          <div class="room-actions">
            <RouterLink v-if="isJoined(item)" class="button primary" :to="'/study-rooms/' + item.id">进入</RouterLink>
            <button v-else-if="item.visibility === 'public' && item.status === 'active'" class="button primary" @click="joinAndEnter(item)">加入并进入</button>
            <span v-else class="muted">私密房间</span>
          </div>
        </article>
        <div v-if="!visibleRooms.length" class="empty-state group-card">还没有正在开放的自习室，创建第一个吧。</div>
      </div>

      <div v-if="showClosed && closedRooms.length" class="closed-block">
        <h2>我已关闭的自习室</h2>
        <div class="room-grid">
          <article v-for="item in closedRooms" :key="'closed-' + item.id" class="group-card room-card">
            <div class="room-top">
              <RouterLink class="room-title" :to="'/study-rooms/' + item.id">{{ item.name }}</RouterLink>
              <span class="badge closed">已关闭</span>
            </div>
            <div class="tile-meta">
              <span>共 {{ item.member_count }} 人</span>
              <span>累计专注 {{ item.focus_minutes }} 分钟</span>
              <span v-if="item.total_minutes">我的专注 {{ item.total_minutes }} 分钟</span>
            </div>
            <div class="room-actions"><RouterLink class="button ghost" :to="'/study-rooms/' + item.id">查看</RouterLink></div>
          </article>
        </div>
      </div>
    </template>

    <template v-else-if="detail">
      <div class="group-card room-head">
        <div>
          <p class="eyebrow">{{ room.subject || '一起专注' }} · {{ room.visibility === 'public' ? '公开自习室' : '私密自习室' }}</p>
          <h1>{{ room.name }}</h1>
          <p>{{ room.goal || '这间自习室还没有写学习目标。' }}</p>
          <div class="head-meta">
            <span class="badge" :class="room.status === 'closed' ? 'closed' : 'active'">{{ room.status === 'closed' ? '已关闭' : '进行中' }}</span>
            <span>在线 {{ onlineCount }} 人 / 共 {{ memberCount }} 人</span>
            <span>累计专注 {{ room.focus_minutes }} 分钟</span>
            <span v-if="room.min_minutes > 0">门槛 {{ room.min_minutes }} 分钟</span>
            <span v-if="room.muted">已开启静音</span>
            <span v-if="groupName">关联小组：{{ groupName }}</span>
            <span v-if="myRole">我的角色：{{ myRole }}</span>
            <span v-if="myEntry">加入于 {{ date(myEntry.joined_at) }}</span>
            <span v-if="onlineNames.length">在线：{{ onlineLabel }}</span>
          </div>
        </div>
        <div class="head-actions">
          <button v-if="!joined && room.status === 'active'" class="button primary" @click="joinRoom">加入</button>
          <button v-if="joined && !isOwner" class="button danger" @click="askLeave"><LogOut :size="14" /> 退出</button>
          <button v-if="isOwner" class="button ghost" @click="showSettings ? showSettings = false : openSettings()">{{ showSettings ? '收起设置' : '房间设置' }}</button>
          <button v-if="isOwner" class="button danger" @click="askDissolve"><Trash2 :size="14" /> 解散房间</button>
        </div>
      </div>

      <form v-if="showSettings && isOwner" class="group-card room-form" @submit.prevent="saveSettings">
        <h2>房间设置</h2>
        <label>自习室名称<input v-model="settingsForm.name" required minlength="2" maxlength="40" /></label>
        <label>学习目标<textarea v-model="settingsForm.goal" rows="3" maxlength="120"></textarea></label>
        <div class="form-two">
          <label>学科<input v-model="settingsForm.subject" maxlength="40" /></label>
          <label>最低专注时长（分钟，0 表示不限）<input v-model.number="settingsForm.minMinutes" type="number" min="0" max="720" /></label>
        </div>
        <fieldset>
          <legend>可见性</legend>
          <label class="choice"><input v-model="settingsForm.visibility" type="radio" value="public" />公开</label>
          <label class="choice"><input v-model="settingsForm.visibility" type="radio" value="private" />私密</label>
        </fieldset>
        <fieldset>
          <legend>房间状态</legend>
          <label class="choice"><input v-model="settingsForm.status" type="radio" value="active" />进行中</label>
          <label class="choice"><input v-model="settingsForm.status" type="radio" value="closed" />关闭房间（会广播一条系统消息）</label>
        </fieldset>
        <label class="choice"><input v-model="settingsForm.muted" type="checkbox" />开启静音：只有房主可以发言</label>
        <div class="form-actions"><button class="button primary" type="submit">保存设置</button></div>
      </form>

      <div class="room-content">
        <main>
          <div class="group-card focus-card">
            <div class="panel-heading">
              <div>
                <h2>专注打卡</h2>
                <p>开始一段专注，结束后会记入房间排行榜与你的专注统计。</p>
              </div>
              <span v-if="room.min_minutes > 0" class="threshold">门槛 {{ room.min_minutes }} 分钟</span>
            </div>
            <div v-if="!timerActive" class="focus-setup">
              <label>专注时长（分钟，1-180）<input v-model.number="focusForm.minutes" type="number" min="1" max="180" /></label>
              <label>本次任务（可选）<input v-model="focusForm.task" maxlength="120" placeholder="例如：做完线代第三章习题" /></label>
              <label>学科（可选）<input v-model="focusForm.subject" maxlength="40" placeholder="例如：数学" /></label>
              <label>备注（可选）<input v-model="focusForm.note" maxlength="255" placeholder="记下这次专注的状态" /></label>
              <button class="button primary" type="button" @click="startTimer"><Play :size="14" /> 开始专注</button>
            </div>
            <div v-else class="focus-running">
              <p class="clock">{{ clock }}</p>
              <p class="timer-hint">{{ timerPhase === 'paused' ? '已暂停，点「继续」接着计时。' : plannedMinutes + ' 分钟专注进行中' }}</p>
              <div class="focus-actions">
                <button v-if="timerPhase === 'focus'" class="button ghost" type="button" @click="pauseTimer"><Pause :size="14" /> 暂停</button>
                <button v-else class="button ghost" type="button" @click="resumeTimer"><Play :size="14" /> 继续</button>
                <button class="button primary" type="button" @click="finishTimer">结束并记录</button>
                <button class="button" type="button" @click="cancelTimer">取消</button>
              </div>
            </div>
          </div>

          <div class="group-card message-card">
            <div class="panel-heading">
              <div>
                <h2>房间留言</h2>
                <p>报个进度、互相鼓励，别打扰正在计时的人。</p>
              </div>
            </div>
            <div class="message-list">
              <p v-if="!messages.length" class="muted">还没有留言，打个招呼吧。</p>
              <p v-else-if="messages.length >= 50" class="muted">只显示最近 50 条留言。</p>
              <div v-for="message in messages" :key="message.id" :class="['message', message.kind === 'system' ? 'system' : '']">
                <template v-if="message.kind === 'system'">
                  <span>{{ message.content }}</span><small>{{ date(message.created_at) }}</small>
                </template>
                <template v-else>
                  <div class="message-head">
                    <b>{{ message.username }}</b><small>{{ date(message.created_at) }}</small>
                    <button v-if="canDeleteMessage(message)" class="text-button danger" type="button" @click="askDeleteMessage(message)"><Trash2 :size="13" /> 删除</button>
                  </div>
                  <p>{{ message.content }}</p>
                </template>
              </div>
            </div>
            <p v-if="speakHint" class="speak-hint">{{ speakHint }}</p>
            <form class="message-form" @submit.prevent="postMessage">
              <input v-model="input" :disabled="!canSpeak" maxlength="200" :placeholder="joined ? '分享你的进度或鼓励一下大家' : '加入后即可发言'" />
              <button class="button primary" type="submit" :disabled="!canSpeak || sending">{{ sending ? '发送中…' : '发送' }}</button>
            </form>
          </div>
        </main>

        <aside>
          <div class="group-card leaderboard-card">
            <div class="panel-heading">
              <div>
                <h2>专注时长榜</h2>
                <p>房间成员与累计专注时长。</p>
              </div>
            </div>
            <div class="leaderboard">
              <div v-for="(entry, index) in board" :key="entry.user_id" :class="['lb-row', Number(entry.user_id) === me ? 'me' : '']">
                <span class="rank">{{ index + 1 }}</span>
                <div><b>{{ entry.username }}</b><small>{{ entry.role === 'owner' ? '房主' : '成员' }} · 加入于 {{ date(entry.joined_at) }}</small></div>
                <span class="minutes">{{ entry.total_minutes }} 分钟</span>
              </div>
              <p v-if="!board.length" class="muted">还没有成员榜数据。</p>
            </div>
          </div>
        </aside>
      </div>
    </template>

    <p v-else-if="loading" class="muted room-loading">正在加载自习室…</p>

    <ConfirmDialog :open="!!pendingDialog" :title="confirmDialog.title" :subject="confirmDialog.subject" :description="confirmDialog.description" :confirm-text="confirmDialog.confirmText" :pending-text="confirmDialog.pendingText" :pending="dialogPending" @cancel="pendingDialog = null" @confirm="runPendingDialog">
      <template #icon>
        <Trash2 v-if="confirmDialog.icon !== 'leave'" :size="18" />
        <LogOut v-else :size="18" />
      </template>
    </ConfirmDialog>
  </section>
</template>

<style scoped>
.rooms-page{max-width:1120px;margin:0 auto;padding:18px 0 60px;color:#203d36}.room-heading,.room-head{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin:10px 0 24px}.room-head{padding:22px;align-items:flex-start}.room-heading h1,.room-head h1{margin:3px 0 8px;font-family:Georgia,serif;font-size:42px;letter-spacing:-1px}.room-heading p,.room-head p{color:#617772;max-width:650px;line-height:1.7}.eyebrow{margin:0;color:#0b7771!important;font-size:11px!important;font-weight:800;letter-spacing:1.8px;text-transform:uppercase}
.button{display:inline-flex;align-items:center;justify-content:center;gap:6px;border:1px solid #bfd5cb;border-radius:7px;padding:10px 15px;background:#fff;color:#20584f;cursor:pointer;text-decoration:none;font-weight:700;font-size:13px}.button.primary{background:#0b7771;border-color:#0b7771;color:#fff}.button.ghost{background:#eff7f3}.button.danger{background:#c94d43;border-color:#c94d43;color:#fff}.button.danger:hover{background:#ad3d35;border-color:#ad3d35}.button:disabled{opacity:.55;cursor:not-allowed}
.group-card{border:1px solid #dce8e1;border-radius:14px;background:#fff;box-shadow:0 10px 25px rgba(31,75,62,.045)}.group-alert{margin:8px 0 14px;padding:12px 15px;border-radius:8px}.group-alert.error{background:#fff2f0;color:#9e463c}.group-alert.success{background:#edf8f2;color:#237358}
.room-filters{display:grid;gap:12px;padding:14px;margin-bottom:20px}.filter-chips{display:flex;gap:8px;flex-wrap:wrap}.chip{border:1px solid #d4e1da;border-radius:20px;padding:7px 15px;background:#fbfdfc;color:#41605a;cursor:pointer;font-weight:700;font-size:13px}.chip.active{background:#0b7771;border-color:#0b7771;color:#fff}.filter-row{display:flex;align-items:center;gap:14px;flex-wrap:wrap}.filter-row input{flex:1;min-width:200px}
.room-filters input,.room-form input,.room-form textarea,.room-form select,.focus-setup input,.message-form input{width:100%;box-sizing:border-box;border:1px solid #d4e1da;border-radius:7px;padding:10px 12px;background:#fbfdfc;color:#263e39}.room-form{display:grid;gap:14px;padding:20px;margin-bottom:20px}.room-form h2{margin:0;font-size:19px}.room-form label{display:block;font-size:13px;font-weight:700;color:#33544c}.room-form input,.room-form textarea,.room-form select,.focus-setup input{margin-top:6px}.form-two{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}.room-form fieldset{margin:0;border:1px solid #e2ece6;border-radius:10px;padding:12px 14px}.room-form legend{padding:0 6px;color:#0b7771;font-size:12px;font-weight:800}.choice{display:flex!important;align-items:center;gap:8px;font-weight:600!important;margin-top:6px}.choice input{width:auto!important;margin:0!important}.form-actions{display:flex;gap:10px}
.room-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}.room-card{display:flex;flex-direction:column;gap:10px;padding:20px;min-height:210px}.room-top{display:flex;align-items:center;justify-content:space-between;gap:10px}.room-title{font-size:17px;font-weight:800;color:#1d3b37;text-decoration:none}.room-title:hover{color:#0b7771}.room-goal{margin:0;color:#617772;font-size:13px;line-height:1.7}.badge{flex:none;border-radius:20px;padding:3px 10px;font-size:11px;font-weight:800}.badge.public{background:#edf8f2;color:#237358}.badge.private{background:#fdf3e6;color:#9a6a22}.badge.active{background:#e8f3ff;color:#2b5f95}.badge.closed{background:#f1f4f3;color:#75857f}.room-tags{display:flex;gap:8px;flex-wrap:wrap;font-size:12px;color:#4c6f66}.room-tags span{border:1px solid #e2ece6;border-radius:20px;padding:3px 10px}.room-tags .role{border-color:#bfe0d3;background:#eff7f3;color:#0b7771;font-weight:700}.tile-meta{display:flex;gap:12px;flex-wrap:wrap;font-size:12px;color:#82968e}.room-actions{margin-top:auto;display:flex;gap:8px;align-items:center}.room-actions .muted{margin:0}.empty-state{padding:38px 20px;text-align:center;color:#82968e;font-size:14px;grid-column:1/-1}.muted{color:#82968e;font-size:13px;margin:6px 0}.closed-block{margin-top:26px}.closed-block h2{margin:0 0 12px;font-size:18px}
.head-meta{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-top:12px;font-size:12px;color:#617772}.head-actions{display:flex;flex-direction:column;gap:8px;flex:none;min-width:132px}
.room-content{display:grid;grid-template-columns:minmax(0,1.3fr) minmax(0,1fr);gap:16px;align-items:start}.room-content main{display:grid;gap:16px;min-width:0}.room-content aside{min-width:0}
.panel-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.panel-heading h2{margin:2px 0 4px;font-size:19px}.panel-heading p{margin:0;color:#82968e;font-size:12px}
.focus-card{display:grid;gap:14px;padding:20px}.threshold{border-radius:20px;background:#eff7f3;color:#0b7771;padding:4px 11px;font-size:12px;font-weight:800;flex:none}.focus-setup{display:grid;gap:12px}.focus-setup label{display:block;font-size:13px;font-weight:700;color:#33544c}.focus-setup .button{justify-self:start}.focus-running{display:grid;gap:10px;justify-items:center;padding:8px 0}.clock{margin:0;font-family:Georgia,serif;font-size:52px;letter-spacing:-1px;color:#0b7771}.timer-hint{margin:0;color:#617772;font-size:13px}.focus-actions{display:flex;gap:10px;flex-wrap:wrap;justify-content:center}
.message-card{display:grid;gap:12px;padding:20px}.message-list{display:grid;gap:10px;max-height:420px;overflow:auto}.message{padding:10px 12px;border:1px solid #e7efea;border-radius:10px;background:#fbfdfc}.message p{margin:6px 0 0;color:#2c4a43;font-size:14px;line-height:1.7;word-break:break-word}.message-head{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.message-head b{font-size:13px;color:#1d3b37}.message-head small{color:#82968e;font-size:12px}.message.system{border:none;background:transparent;text-align:center;color:#82968e;font-size:12px}.message.system small{margin-left:8px}
.text-button{border:none;background:none;padding:0;color:#0b7771;font-weight:700;font-size:12px;cursor:pointer;display:inline-flex;align-items:center;gap:4px}.text-button.danger{color:#b6574d;margin-left:auto}.speak-hint{margin:0;padding:9px 12px;border-radius:8px;background:#fdf3e6;color:#9a6a22;font-size:12px}.message-form{display:flex;gap:10px}.message-form input:disabled{background:#f2f5f4;color:#9aaba5}
.leaderboard-card{display:grid;gap:12px;padding:20px}.leaderboard{display:grid;gap:8px}.lb-row{display:flex;align-items:center;gap:10px;padding:9px 11px;border:1px solid #e7efea;border-radius:10px}.lb-row.me{border-color:#bfe0d3;background:#eff7f3}.lb-row .rank{flex:none;width:22px;text-align:center;font-weight:800;color:#0b7771}.lb-row b{font-size:13px;color:#1d3b37}.lb-row small{display:block;color:#82968e;font-size:11px}.lb-row .minutes{margin-left:auto;font-weight:800;color:#20584f;font-size:13px;white-space:nowrap}
@media(max-width:900px){.room-grid{grid-template-columns:repeat(2,1fr)}.room-content{grid-template-columns:1fr}.room-head{flex-direction:column}.head-actions{flex-direction:row;flex-wrap:wrap}.form-two{grid-template-columns:1fr 1fr}}
@media(max-width:620px){.rooms-page{padding:10px}.room-heading,.room-head{align-items:flex-start;flex-direction:column}.room-heading h1,.room-head h1{font-size:32px}.room-grid,.form-two{grid-template-columns:1fr}.room-head{padding:18px}.clock{font-size:42px}.message-form{flex-wrap:wrap}}
</style>
