<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Activity, CalendarDays, ChartColumn, Check, Clock, Flame, History, Minus, Pause, Play, Plus, RotateCcw, SkipForward, Sparkles, Target, Timer, TrendingUp, Trophy } from 'lucide-vue-next'
import { api, date, send } from '../api'
import { useSession } from '../store'
import ConfirmDialog from './ConfirmDialog.vue'

type FocusRange = 'day' | 'week' | 'month' | 'year' | 'all'
type TimerPhase = 'focus' | 'break'
type SessionPage = { items: any[]; hasNext: boolean; page: number }
type SessionForm = { subject: string; task: string; note: string; roomId: string; planId: string; taskId: string }

const RING_RADIUS = 96
const RING_LENGTH = 2 * Math.PI * RING_RADIUS
const STORAGE_KEY = 'focus-timer'
const MAX_SPAN_MS = 1440 * 60000
const RANGES: { value: FocusRange; label: string }[] = [
  { value: 'day', label: '今日' },
  { value: 'week', label: '本周' },
  { value: 'month', label: '本月' },
  { value: 'year', label: '今年' },
  { value: 'all', label: '全部' }
]
const BOARD_RANGES: { value: string; label: string }[] = [
  { value: 'week', label: '本周' },
  { value: 'month', label: '本月' },
  { value: 'all', label: '总榜' }
]
const SUBJECTS = ['数学', '英语', '编程', '专业课', '考研', '其他']
const SOURCE_LABELS: Record<string, string> = { timer: '番茄钟', manual: '手动补记', room: '自习室', plan: '计划' }

const route = useRoute(), session = useSession()
const isStats = computed(() => route.path === '/focus/stats')

const error = ref(''), notice = ref(''), loading = ref(false), saving = ref(false), recording = ref(false)
const rooms = ref<any[]>([]), plans = ref<any[]>([]), planTasks = ref<any[]>([])
const dayStats = ref<any>(null), stats = ref<any>(null), board = ref<any>(null)
const recent = ref<SessionPage>({ items: [], hasNext: false, page: 1 })
const rangeSessions = ref<SessionPage>({ items: [], hasNext: false, page: 1 })
const range = ref<FocusRange>('week'), boardRange = ref('week')
const boardPage = ref(1), recentPage = ref(1), historyPage = ref(1)
const showManual = ref(false)

const focusForm = ref<SessionForm>({ subject: '', task: '', note: '', roomId: '', planId: '', taskId: '' })
const manualForm = ref({ durationMinutes: 25, at: localInput(Date.now()), subject: '', task: '', note: '', roomId: '', planId: '', taskId: '' })
const syncPlanDone = ref(true)

const phase = ref<TimerPhase>('focus'), focusMinutes = ref(25), breakMinutes = ref(5)
const started = ref(false), running = ref(false), endsAt = ref(0), remaining = ref(0)
const phaseStartedAt = ref(0), pausedMs = ref(0), pausedAt = ref(0)

const pendingAction = ref<'reset' | 'end' | null>(null), actionPending = ref(false)
let ticker: number | undefined, noticeTimer: number | undefined

function pad(value: number) { return String(value).padStart(2, '0') }
function clampMinutes(value: unknown) { const next = Math.round(Number(value)); return Number.isFinite(next) ? Math.min(180, Math.max(1, next)) : 25 }
function localIso(ms: number) {
  const value = new Date(ms)
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}T${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(value.getSeconds())}`
}
function localInput(ms: number) {
  const value = new Date(ms)
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(value.getDate())}T${pad(value.getHours())}:${pad(value.getMinutes())}`
}
function parseTime(value: string) { return new Date(String(value || '').replace(' ', 'T')) }
function clock(value: string) { const at = parseTime(value); return Number.isNaN(at.getTime()) ? '' : pad(at.getHours()) + ':' + pad(at.getMinutes()) }
function dayText(value: string) { const at = parseTime(value); return Number.isNaN(at.getTime()) ? '' : date(at.toISOString()) }
function formatMinutes(value: unknown) {
  const total = Math.max(0, Math.round(Number(value) || 0))
  return total >= 60 ? `${Math.floor(total / 60)} 小时 ${total % 60} 分` : `${total} 分钟`
}
function setNotice(message: string) {
  notice.value = message
  if (noticeTimer) clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => { notice.value = '' }, 3000)
}
function sourceLabel(source: string) { return SOURCE_LABELS[source] || '专注' }
function emptyPage(): SessionPage { return { items: [], hasNext: false, page: 1 } }

const totalMs = computed(() => clampMinutes(phase.value === 'focus' ? focusMinutes.value : breakMinutes.value) * 60000)
const displayedMs = computed(() => (started.value ? Math.max(0, remaining.value) : totalMs.value))
const progress = computed(() => {
  const total = totalMs.value
  return total > 0 ? Math.min(1, Math.max(0, 1 - displayedMs.value / total)) : 0
})
const ringOffset = computed(() => RING_LENGTH * (1 - progress.value))
const clockText = computed(() => {
  const total = Math.ceil(displayedMs.value / 1000)
  return pad(Math.floor(total / 60)) + ':' + pad(total % 60)
})
const phaseLabel = computed(() => (phase.value === 'focus' ? '专注' : '休息'))
const stateLabel = computed(() => running.value ? phaseLabel.value + '中' : started.value ? '已暂停' : '待开始')
const activeHistory = computed(() => (isStats.value ? rangeSessions.value : recent.value))
const activePage = computed(() => (isStats.value ? historyPage.value : recentPage.value))
const rangeLabel = computed(() => RANGES.find(item => item.value === range.value)?.label || '本周')

type DayBar = { date: string; minutes: number; sessions: number; height: number }
type BoardRow = { id: number | string; username: string; minutes: number; sessions: number; rank: number; mine: boolean }

const dayBars = computed<DayBar[]>(() => {
  const days = (stats.value?.days || []).slice(-30)
  const max = Math.max(1, ...days.map((item: any) => Number(item.minutes) || 0))
  return days.map((item: any) => {
    const minutes = Number(item.minutes) || 0
    return { date: String(item.date || ''), minutes, sessions: Number(item.sessions) || 0, height: Math.round(minutes / max * 100) }
  })
})
const dayBarTotal = computed(() => dayBars.value.reduce((sum, item) => sum + item.minutes, 0))
const showDayLabels = computed(() => dayBars.value.length <= 14)

const hourBars = computed(() => {
  const minutes = Array.from({ length: 24 }, () => 0)
  for (const cell of stats.value?.heatmap || []) {
    const hour = Number(cell.hour)
    if (Number.isFinite(hour) && hour >= 0 && hour < 24) minutes[hour] += Number(cell.minutes) || 0
  }
  const max = Math.max(1, ...minutes)
  return minutes.map((value, hour) => ({
    hour,
    label: pad(hour) + ':00',
    minutes: value,
    height: value > 0 ? Math.max(3, Math.round(value / max * 100)) : 0
  }))
})
const hourTotal = computed(() => hourBars.value.reduce((sum, item) => sum + item.minutes, 0))
const bestHourText = computed(() => {
  const value = stats.value?.bestHour
  if (value === null || value === undefined) return ''
  const hour = Number(value)
  return Number.isFinite(hour) ? `最高效时段 ${pad(hour)}:00-${pad(Math.min(24, hour + 1))}:00` : ''
})
const subjectBars = computed(() => {
  const items = stats.value?.subjects || []
  const minutes = items.map((item: any) => Number(item.minutes) || 0)
  const max = Math.max(1, ...minutes)
  const total = minutes.reduce((sum: number, value: number) => sum + value, 0) || 1
  return items.map((item: any, index: number) => ({
    subject: item.subject || '未填写学科',
    minutes: minutes[index],
    sessions: Number(item.sessions) || 0,
    width: Math.max(4, Math.round(minutes[index] / max * 100)),
    percent: Math.round(minutes[index] / total * 100)
  }))
})
const boardRows = computed<BoardRow[]>(() => (board.value?.items || []).map((row: any, index: number) => ({
  id: row.user_id,
  username: row.username,
  minutes: Number(row.range_minutes) || 0,
  sessions: Number(row.range_sessions) || 0,
  rank: (boardPage.value - 1) * 20 + index + 1,
  mine: row.user_id === session.user?.id
})))
const myRank = computed(() => boardRows.value.find(row => row.mine)?.rank ?? null)

const confirmDialog = computed(() => pendingAction.value === 'end'
  ? { title: '结束本轮专注', description: '将按已经专注的时间记录一次专注，然后回到新的专注阶段。', confirmText: '结束并记录', pendingText: '记录中…' }
  : { title: '重置计时', description: '当前阶段的进度会清零，这段还没记录的专注不会保存。', confirmText: '确认重置', pendingText: '重置中…' })

function phaseElapsedMs(now = Date.now()) {
  if (!phaseStartedAt.value) return 0
  const paused = pausedMs.value + (pausedAt.value ? now - pausedAt.value : 0)
  return Math.max(0, now - phaseStartedAt.value - paused)
}
function persist() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      phase: phase.value, started: started.value, running: running.value, endsAt: endsAt.value,
      remaining: remaining.value, phaseStartedAt: phaseStartedAt.value, pausedMs: pausedMs.value,
      pausedAt: pausedAt.value, focusMinutes: focusMinutes.value, breakMinutes: breakMinutes.value
    }))
  } catch { /* 隐私模式下 localStorage 不可用时静默降级 */ }
}
function clearSaved() { try { localStorage.removeItem(STORAGE_KEY) } catch { /* 忽略存储清理失败 */ } }
function restore() {
  let saved: any = null
  try { const raw = localStorage.getItem(STORAGE_KEY); saved = raw ? JSON.parse(raw) : null } catch { saved = null }
  if (!saved || (saved.phase !== 'focus' && saved.phase !== 'break')) return
  const startMs = Number(saved.phaseStartedAt) || 0
  if (saved.started && (!startMs || Date.now() - startMs > MAX_SPAN_MS)) { clearSaved(); return }
  phase.value = saved.phase
  focusMinutes.value = clampMinutes(saved.focusMinutes ?? 25)
  breakMinutes.value = clampMinutes(saved.breakMinutes ?? 5)
  started.value = !!saved.started
  running.value = !!saved.running && !!saved.started
  endsAt.value = Number(saved.endsAt) || 0
  remaining.value = Math.max(0, Number(saved.remaining) || 0)
  phaseStartedAt.value = startMs
  pausedMs.value = Math.max(0, Number(saved.pausedMs) || 0)
  pausedAt.value = Number(saved.pausedAt) || 0
  if (running.value) remaining.value = Math.max(0, endsAt.value - Date.now())
}
function begin() {
  if (running.value) return
  if (!started.value) {
    remaining.value = totalMs.value
    phaseStartedAt.value = Date.now()
    pausedMs.value = 0
    pausedAt.value = 0
  } else if (pausedAt.value) {
    pausedMs.value += Date.now() - pausedAt.value
    pausedAt.value = 0
  }
  started.value = true
  running.value = true
  endsAt.value = Date.now() + remaining.value
  persist()
}
function pause() {
  if (!running.value) return
  remaining.value = Math.max(0, endsAt.value - Date.now())
  running.value = false
  pausedAt.value = Date.now()
  persist()
}
function toggleTimer() { running.value ? pause() : begin() }
function setPhase(next: TimerPhase) {
  phase.value = next
  started.value = false
  running.value = false
  endsAt.value = 0
  remaining.value = 0
  phaseStartedAt.value = 0
  pausedMs.value = 0
  pausedAt.value = 0
  persist()
}
function resetTimer() { setPhase(phase.value); ElMessage({ type: 'info', message: '计时已重置' }) }
function skipBreak() {
  if (phase.value !== 'break') return
  setPhase('focus')
  begin()
  ElMessage({ type: 'info', message: '已跳过休息，开始新的专注' })
}
function bumpFocus(delta: number) { focusMinutes.value = clampMinutes(focusMinutes.value + delta) }
function bumpBreak(delta: number) { breakMinutes.value = clampMinutes(breakMinutes.value + delta) }
function clampFocusInput() { focusMinutes.value = clampMinutes(focusMinutes.value) }
function clampBreakInput() { breakMinutes.value = clampMinutes(breakMinutes.value) }
function tick() {
  if (!running.value) return
  remaining.value = Math.max(0, endsAt.value - Date.now())
  if (remaining.value <= 0) completePhase().catch(() => { /* 记录阶段已在内部处理错误 */ })
}

function payloadFrom(form: SessionForm, minutes: number, startMs: number, endMs: number, source: string) {
  return {
    durationMinutes: minutes,
    subject: form.subject.trim(),
    task: form.task.trim(),
    note: form.note.trim(),
    startedAt: localIso(startMs),
    endedAt: localIso(endMs),
    roomId: Number(form.roomId) || null,
    planId: Number(form.planId) || null,
    taskId: Number(form.taskId) || null,
    source
  }
}
async function record(payload: Record<string, unknown>, message: string) {
  const result = await send<any>('/focus/sessions', 'POST', payload)
  const points = Number(result?.pointsAwarded ?? result?.points ?? 0)
  const badges: any[] = Array.isArray(result?.badges) ? result.badges : []
  ElMessage({ type: 'success', message: points > 0 ? `${message}，+${points} 积分` : message })
  if (badges.length) ElMessage({ type: 'success', message: '获得新徽章：' + badges.map(badge => badge?.name || badge?.code || String(badge)).join('、') })
  setNotice(`已记录：${message}`)
  return result
}
async function completePlanTask() {
  const planId = Number(focusForm.value.planId) || 0, taskId = Number(focusForm.value.taskId) || 0
  if (!planId || !taskId || !syncPlanDone.value) return
  const task = planTasks.value.find(item => item.id === taskId)
  if (task && Number(task.child_count) > 0) {
    ElMessage({ type: 'warning', message: '该计划任务包含子任务，请在计划页手动勾选子任务' })
    return
  }
  try {
    await send('/plans/' + planId + '/tasks/' + taskId, 'PATCH', { status: 'done' })
    focusForm.value.taskId = ''
    await loadPlanTasks()
    ElMessage({ type: 'success', message: '计划任务已同步勾选完成' })
  } catch (exception) {
    ElMessage({ type: 'warning', message: '专注已记录，但计划任务勾选失败：' + (exception as Error).message })
  }
}
async function recordFocus(elapsedMs: number, endMs: number, startMs: number) {
  if (!session.user) { setNotice('计时完成，登录后即可记录专注时长'); return }
  if (elapsedMs < 60000) { setNotice('本次专注不足 1 分钟，未计入记录'); return }
  const minutes = Math.min(720, Math.max(1, Math.ceil(elapsedMs / 60000)))
  let from = startMs || endMs - minutes * 60000
  if (endMs - from > MAX_SPAN_MS || endMs - from < 0) from = endMs - minutes * 60000
  recording.value = true
  try {
    await record(payloadFrom(focusForm.value, minutes, from, endMs, 'timer'), `本次专注 ${minutes} 分钟`)
    await completePlanTask()
    await load()
  } catch (exception) {
    error.value = (exception as Error).message
  } finally {
    recording.value = false
  }
}
async function completePhase() {
  const now = Date.now()
  running.value = false
  if (phase.value === 'focus') {
    const startMs = phaseStartedAt.value
    const elapsed = phaseElapsedMs(now)
    setPhase('break')
    begin()
    ElMessage({ type: 'success', message: '专注结束，休息一下' })
    await recordFocus(elapsed, now, startMs)
  } else {
    setPhase('focus')
    begin()
    ElMessage({ type: 'success', message: '休息结束，开始新一轮专注' })
  }
}
async function runPendingAction() {
  const action = pendingAction.value
  if (!action || actionPending.value) return
  actionPending.value = true
  error.value = ''
  try {
    if (action === 'reset') resetTimer()
    else {
      const now = Date.now()
      const startedMs = phaseStartedAt.value
      const elapsed = phaseElapsedMs(now)
      setPhase('focus')
      await recordFocus(elapsed, now, startedMs)
    }
  } catch (exception) {
    error.value = (exception as Error).message
  } finally {
    actionPending.value = false
    pendingAction.value = null
  }
}

function mergeRooms(payload: any) {
  const source = Array.isArray(payload?.items) ? payload.items : [...(payload?.participated || []), ...(payload?.owned || [])]
  const list: any[] = [], seen = new Set<number>()
  for (const room of source) {
    if (!room || !room.id || seen.has(room.id)) continue
    seen.add(room.id)
    list.push(room)
  }
  return list
}
async function loadOptions() {
  if (!session.user) { rooms.value = []; plans.value = []; return }
  const [roomResult, planResult] = await Promise.allSettled([api('/focus/me/rooms'), api('/plans?status=active')])
  rooms.value = roomResult.status === 'fulfilled' ? mergeRooms(roomResult.value) : []
  plans.value = planResult.status === 'fulfilled' ? (planResult.value?.items || []) : []
}
async function loadPlanTasks() {
  planTasks.value = []
  const planId = Number(focusForm.value.planId) || 0
  if (!planId) return
  try {
    const data = await api('/plans/' + planId)
    planTasks.value = (data?.tasks || []).filter((task: any) => task.status === 'pending')
  } catch (exception) {
    error.value = (exception as Error).message
  }
}
async function loadSessions(target: 'recent' | 'range', page: number) {
  if (!session.user) {
    if (target === 'recent') recent.value = emptyPage(); else rangeSessions.value = emptyPage()
    return
  }
  const useRange = target === 'recent' ? 'week' : range.value
  const data = await api<SessionPage>('/focus/sessions?range=' + useRange + '&page=' + Math.max(1, page))
  const next: SessionPage = { items: data?.items || [], hasNext: !!data?.hasNext, page: Number(data?.page) || 1 }
  if (target === 'recent') { recent.value = next; recentPage.value = next.page } else { rangeSessions.value = next; historyPage.value = next.page }
}
async function loadBoard() {
  try {
    board.value = await api('/focus/leaderboard?range=' + boardRange.value + '&page=' + boardPage.value)
  } catch (exception) {
    board.value = null
    error.value = (exception as Error).message
  }
}
async function load() {
  loading.value = true
  error.value = ''
  try {
    if (isStats.value) {
      stats.value = session.user ? await api('/focus/stats?range=' + range.value) : null
      await loadSessions('range', historyPage.value)
      await loadBoard()
      return
    }
    await loadOptions()
    dayStats.value = session.user ? await api('/focus/stats?range=day') : null
    await loadSessions('recent', recentPage.value)
  } catch (exception) {
    error.value = (exception as Error).message
  } finally {
    loading.value = false
  }
}
async function changeRange(next: FocusRange) {
  if (range.value === next) return
  range.value = next
  historyPage.value = 1
  await load()
}
async function changeBoardRange(next: string) {
  if (boardRange.value === next) return
  boardRange.value = next
  boardPage.value = 1
  await loadBoard()
}
async function goBoard(delta: number) {
  const next = boardPage.value + delta
  if (next < 1 || (delta > 0 && !board.value?.hasNext)) return
  boardPage.value = next
  await loadBoard()
}
async function goHistory(delta: number) {
  const next = activePage.value + delta
  if (next < 1 || (delta > 0 && !activeHistory.value.hasNext)) return
  error.value = ''
  if (isStats.value) {
    historyPage.value = next
    await loadSessions('range', next)
  } else {
    recentPage.value = next
    await loadSessions('recent', next)
  }
}
async function submitManual() {
  error.value = ''
  const minutes = Math.round(Number(manualForm.value.durationMinutes) || 0)
  if (!Number.isFinite(minutes) || minutes < 1 || minutes > 720) { error.value = '专注时长需要在 1 到 720 分钟之间'; return }
  const startMs = manualForm.value.at ? new Date(manualForm.value.at).getTime() : Date.now()
  if (!Number.isFinite(startMs)) { error.value = '请选择有效的开始时间'; return }
  const endMs = startMs + minutes * 60000
  saving.value = true
  try {
    await record(payloadFrom(manualForm.value, minutes, startMs, endMs, 'manual'), `已补记 ${minutes} 分钟专注`)
    manualForm.value = { durationMinutes: 25, at: localInput(Date.now()), subject: '', task: '', note: '', roomId: '', planId: '', taskId: '' }
    showManual.value = false
    await load()
  } catch (exception) {
    error.value = (exception as Error).message
  } finally {
    saving.value = false
  }
}

watch(() => route.fullPath, () => { void load() })
watch(() => focusForm.value.planId, async () => {
  focusForm.value.taskId = ''
  await loadPlanTasks()
  const plan = plans.value.find(item => item.id === Number(focusForm.value.planId))
  if (plan && !focusForm.value.subject.trim()) focusForm.value.subject = plan.subject || ''
})

onMounted(async () => {
  restore()
  ticker = window.setInterval(tick, 250)
  if (started.value && running.value && endsAt.value <= Date.now()) tick()
  await load()
})
onUnmounted(() => {
  if (ticker) clearInterval(ticker)
  if (noticeTimer) clearTimeout(noticeTimer)
})
</script>

<template>
  <section class="focus-page">
    <div v-if="error" class="group-alert error">{{ error }}</div>
    <div v-if="notice" class="group-alert success">{{ notice }}</div>

    <div class="focus-heading">
      <div class="focus-intro">
        <p class="eyebrow">{{ isStats ? 'FOCUS INSIGHT' : 'DEEP WORK TIMER' }}</p>
        <h1>{{ isStats ? '专注统计' : '番茄钟' }}</h1>
        <p>{{ isStats ? '看清时间去了哪里：每日曲线、高效时段、学科分布与社区排行。' : '按绝对时间计时，切换标签页也不会走时；一轮结束自动记录时长、积分与学习计划进度。' }}</p>
      </div>
      <nav class="focus-tabs">
        <RouterLink to="/focus" :class="{ active: !isStats }">番茄钟</RouterLink>
        <RouterLink to="/focus/stats" :class="{ active: isStats }">专注统计</RouterLink>
      </nav>
    </div>

    <p v-if="loading" class="loading-line">正在加载专注数据…</p>

    <template v-if="isStats">
      <div class="range-bar">
        <button v-for="item in RANGES" :key="item.value" type="button" :class="['range-button', { active: range === item.value }]" @click="changeRange(item.value)">{{ item.label }}</button>
      </div>

      <div v-if="!session.user" class="group-card login-card">
        <p>登录后即可查看你的专注统计、时段分布与学科占比。</p>
        <RouterLink class="button primary" to="/login?next=/focus/stats">去登录</RouterLink>
      </div>

      <template v-else>
        <div class="summary-grid">
          <div class="group-card summary-card"><CalendarDays :size="16" /><span>今日</span><b>{{ formatMinutes(stats?.today) }}</b></div>
          <div class="group-card summary-card"><Flame :size="16" /><span>本周</span><b>{{ formatMinutes(stats?.week) }}</b></div>
          <div class="group-card summary-card"><Clock :size="16" /><span>本月</span><b>{{ formatMinutes(stats?.month) }}</b></div>
          <div class="group-card summary-card"><Sparkles :size="16" /><span>累计</span><b>{{ formatMinutes(stats?.total) }}</b></div>
          <div class="group-card summary-card"><Activity :size="16" /><span>{{ rangeLabel }}次数</span><b>{{ Number(stats?.sessions) || 0 }} 次</b></div>
          <div class="group-card summary-card"><ChartColumn :size="16" /><span>{{ rangeLabel }}平均</span><b>{{ formatMinutes(stats?.average) }}</b></div>
          <div class="group-card summary-card"><Target :size="16" /><span>{{ rangeLabel }}最长</span><b>{{ formatMinutes(stats?.longest) }}</b></div>
        </div>

        <div class="chart-grid">
          <div class="group-card chart-card">
            <div class="card-head">
              <h2><ChartColumn :size="16" />每日专注</h2>
              <span>{{ rangeLabel }} · 单位分钟</span>
            </div>
            <div v-if="dayBars.length && dayBarTotal > 0" class="bar-chart">
              <div v-for="bar in dayBars" :key="bar.date" class="bar-column" :title="bar.date + ' · ' + bar.minutes + ' 分钟 / ' + bar.sessions + ' 次'">
                <b v-if="bar.minutes" class="bar-value">{{ bar.minutes }}</b>
                <div class="bar-track"><i :style="{ height: bar.height + '%' }" /></div>
                <small v-if="showDayLabels">{{ bar.date.slice(5) }}</small>
              </div>
            </div>
            <p v-else class="empty-line">暂无专注记录</p>
          </div>

          <div class="group-card chart-card">
            <div class="card-head">
              <h2><Clock :size="16" />时段分布</h2>
              <span>{{ bestHourText || '0-23 时' }}</span>
            </div>
            <div v-if="hourTotal > 0" class="hour-chart">
              <div v-for="bar in hourBars" :key="bar.hour" class="hour-column" :title="bar.label + ' · ' + bar.minutes + ' 分钟'">
                <div class="bar-track"><i :style="{ height: bar.height + '%' }" /></div>
                <small v-if="bar.hour % 3 === 0">{{ bar.hour }}</small>
              </div>
            </div>
            <p v-else class="empty-line">还没有足够的时段数据</p>
          </div>
        </div>

        <div class="chart-grid">
          <div class="group-card chart-card">
            <div class="card-head"><h2><Target :size="16" />学科分布</h2><span>{{ rangeLabel }}</span></div>
            <div v-if="subjectBars.length" class="subject-list">
              <div v-for="item in subjectBars" :key="item.subject" class="subject-row">
                <b>{{ item.subject }}</b>
                <div class="subject-track"><i :style="{ width: item.width + '%' }" /></div>
                <span>{{ item.minutes }} 分 · {{ item.percent }}%</span>
              </div>
            </div>
            <p v-else class="empty-line">记录专注时填写学科，就能看到学科分布</p>
          </div>

          <div class="group-card chart-card">
            <div class="card-head"><h2><Trophy :size="16" />专注排行</h2><span>每页 20 人</span></div>
            <div class="range-bar small">
              <button v-for="item in BOARD_RANGES" :key="item.value" type="button" :class="['range-button', { active: boardRange === item.value }]" @click="changeBoardRange(item.value)">{{ item.label }}</button>
            </div>
            <div v-if="board?.mine" class="my-rank">
              <span>我的排名</span>
              <b>{{ myRank ? '第 ' + myRank + ' 名' : '20 名之外' }}</b>
              <small>{{ formatMinutes(board.mine.minutes) }} · {{ Number(board.mine.sessions) || 0 }} 次</small>
            </div>
            <div v-if="boardRows.length" class="board-list">
              <div v-for="row in boardRows" :key="row.id" :class="['board-row', { me: row.mine }]">
                <span class="board-rank">{{ row.rank }}</span>
                <b>{{ row.username }}</b>
                <span>{{ formatMinutes(row.minutes) }}</span>
                <small>{{ row.sessions }} 次</small>
              </div>
            </div>
            <p v-else class="empty-line">这个时间段还没有人上榜</p>
            <div class="pager">
              <button class="text-button" type="button" :disabled="boardPage <= 1" @click="goBoard(-1)">上一页</button>
              <span>第 {{ boardPage }} 页</span>
              <button class="text-button" type="button" :disabled="!board?.hasNext" @click="goBoard(1)">下一页</button>
            </div>
          </div>
        </div>
      </template>
    </template>

    <template v-else>
      <div class="focus-grid">
        <div class="group-card timer-card">
          <div class="timer-head">
            <span :class="['phase-pill', phase]"><Timer :size="14" />{{ stateLabel }}</span>
            <span class="timer-hint">{{ started ? (running ? '倒计时进行中' : '已暂停，点「继续」接着走') : '设置好时长后点「开始」' }}</span>
          </div>
          <div class="ring-wrap">
            <svg class="ring" :class="phase" viewBox="0 0 220 220" role="img" aria-label="倒计时进度">
              <g transform="rotate(-90 110 110)">
                <circle class="ring-track" cx="110" cy="110" :r="RING_RADIUS" />
                <circle class="ring-value" cx="110" cy="110" :r="RING_RADIUS" :stroke-dasharray="RING_LENGTH" :stroke-dashoffset="ringOffset" />
              </g>
            </svg>
            <div class="ring-copy">
              <strong>{{ clockText }}</strong>
              <small>{{ phaseLabel }} · 共 {{ totalMs / 60000 }} 分钟</small>
            </div>
          </div>
          <div class="timer-actions">
            <button class="button primary" type="button" :disabled="recording" @click="toggleTimer">
              <Pause v-if="running" :size="16" /><Play v-else :size="16" />{{ running ? '暂停' : started ? '继续' : '开始' }}
            </button>
            <button class="button ghost" type="button" :disabled="phase !== 'focus' || !started || recording" @click="pendingAction = 'end'"><Check :size="16" />结束本轮</button>
            <button v-if="phase === 'break'" class="button ghost" type="button" :disabled="recording" @click="skipBreak"><SkipForward :size="16" />跳过休息</button>
            <button class="button ghost" type="button" :disabled="recording" @click="pendingAction = 'reset'"><RotateCcw :size="16" />重置</button>
          </div>
          <div class="duration-row">
            <div class="duration-field">
              <span>专注</span>
              <button type="button" class="step" :disabled="started" @click="bumpFocus(-5)"><Minus :size="13" /></button>
              <input v-model.number="focusMinutes" type="number" min="1" max="180" :disabled="started" @change="clampFocusInput" />
              <button type="button" class="step" :disabled="started" @click="bumpFocus(5)"><Plus :size="13" /></button>
              <em>分钟</em>
            </div>
            <div class="duration-field">
              <span>休息</span>
              <button type="button" class="step" :disabled="started" @click="bumpBreak(-1)"><Minus :size="13" /></button>
              <input v-model.number="breakMinutes" type="number" min="1" max="180" :disabled="started" @change="clampBreakInput" />
              <button type="button" class="step" :disabled="started" @click="bumpBreak(1)"><Plus :size="13" /></button>
              <em>分钟</em>
            </div>
          </div>
          <p class="timer-note">专注与休息都可自定义 1–180 分钟；本轮开始后需先「重置」才能改时长。</p>
        </div>

        <div class="group-card focus-form">
          <div class="card-head"><h2>本次专注信息</h2><span>记录到时长里</span></div>
          <label class="field">学科<input v-model="focusForm.subject" list="focus-subject-options" maxlength="40" placeholder="例如：数学"></label>
          <datalist id="focus-subject-options"><option v-for="item in SUBJECTS" :key="item" :value="item" /></datalist>
          <label class="field">任务名称<input v-model="focusForm.task" maxlength="120" placeholder="例如：高数第三章习题"></label>
          <label class="field">备注<textarea v-model="focusForm.note" rows="3" maxlength="255" placeholder="方法、难点或下一步"></textarea></label>
          <label class="field">自习室<select v-model="focusForm.roomId"><option value="">不加入自习室</option><option v-for="room in rooms" :key="room.id" :value="String(room.id)">{{ room.name }}{{ room.status && room.status !== 'active' ? '（' + room.status + '）' : '' }}</option></select></label>
          <label class="field">学习计划<select v-model="focusForm.planId"><option value="">不关联计划</option><option v-for="plan in plans" :key="plan.id" :value="String(plan.id)">{{ plan.title }}（{{ Number(plan.progress?.percent) || 0 }}%）</option></select></label>
          <label v-if="focusForm.planId" class="field">计划任务<select v-model="focusForm.taskId"><option value="">暂不选择</option><option v-for="task in planTasks" :key="task.id" :value="String(task.id)" :disabled="Number(task.child_count) > 0">{{ task.title }}{{ task.due_date ? ' · ' + task.due_date : '' }}{{ Number(task.child_count) > 0 ? '（含子任务）' : '' }}</option></select></label>
          <label v-if="focusForm.planId" class="choice"><input v-model="syncPlanDone" type="checkbox">完成后同步勾选计划任务</label>
          <p v-if="!session.user" class="muted"><RouterLink to="/login?next=/focus">登录</RouterLink>后，专注结束会自动计时长、积分与徽章。</p>
        </div>
      </div>

      <div class="group-card today-card">
        <div class="card-head"><h2><TrendingUp :size="16" />今日概览</h2><RouterLink to="/focus/stats">查看统计</RouterLink></div>
        <div v-if="dayStats" class="stat-strip">
          <div><span>今日专注</span><b>{{ formatMinutes(dayStats.today) }}</b></div>
          <div><span>今日次数</span><b>{{ Number(dayStats.sessions) || 0 }} 次</b></div>
          <div><span>本周</span><b>{{ formatMinutes(dayStats.week) }}</b></div>
          <div><span>累计</span><b>{{ formatMinutes(dayStats.total) }}</b></div>
          <div><span>平均每次</span><b>{{ formatMinutes(dayStats.average) }}</b></div>
          <div><span>最长一次</span><b>{{ formatMinutes(dayStats.longest) }}</b></div>
        </div>
        <p v-else class="empty-line">登录后即可看到今日、本周与累计的专注概览</p>
      </div>

      <div class="group-card manual-card">
        <div class="card-head">
          <h2><Plus :size="16" />手动补记</h2>
          <button class="text-button" type="button" @click="showManual = !showManual">{{ showManual ? '收起' : '展开' }}</button>
        </div>
        <form v-if="showManual" class="manual-form" @submit.prevent="submitManual">
          <div class="form-two">
            <label class="field">专注时长（分钟）<input v-model.number="manualForm.durationMinutes" type="number" min="1" max="720" required></label>
            <label class="field">开始时间<input v-model="manualForm.at" type="datetime-local" required></label>
            <label class="field">学科<input v-model="manualForm.subject" list="focus-subject-options" maxlength="40" placeholder="例如：英语"></label>
            <label class="field">任务名称<input v-model="manualForm.task" maxlength="120" placeholder="例如：背单词 200 个"></label>
          </div>
          <label class="field">备注<textarea v-model="manualForm.note" rows="2" maxlength="255" placeholder="补记说明（可选）"></textarea></label>
          <button class="button primary" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存补记' }}</button>
        </form>
      </div>
    </template>

    <div class="group-card history-card">
      <div class="card-head">
        <h2><History :size="16" />{{ isStats ? '专注记录' : '最近专注记录' }}</h2>
        <span>{{ isStats ? rangeLabel : '本周' }}</span>
      </div>
      <p v-if="!session.user" class="empty-line">登录后可以查看自己的专注记录</p>
      <template v-else>
        <div v-if="activeHistory.items.length" class="session-list">
          <article v-for="item in activeHistory.items" :key="item.id" class="session-row">
            <div class="session-main">
              <b>{{ item.subject || '专注' }}</b>
              <small>{{ item.task || '未填写任务' }} · {{ date(item.started_at) }} {{ clock(item.started_at) }}<template v-if="item.room_name"> · {{ item.room_name }}</template><template v-if="item.plan_title"> · {{ item.plan_title }}</template></small>
            </div>
            <span :class="['source-pill', item.source]">{{ sourceLabel(item.source) }}</span>
            <b class="session-minutes">{{ Number(item.duration_minutes) || 0 }} 分钟</b>
          </article>
        </div>
        <p v-else class="empty-line">这段时间还没有专注记录</p>
        <div class="pager">
          <button class="text-button" type="button" :disabled="activePage <= 1" @click="goHistory(-1)">上一页</button>
          <span>第 {{ activePage }} 页</span>
          <button class="text-button" type="button" :disabled="!activeHistory.hasNext" @click="goHistory(1)">下一页</button>
        </div>
      </template>
    </div>

    <ConfirmDialog :open="pendingAction !== null" :title="confirmDialog.title" :description="confirmDialog.description" :subject="phaseLabel + ' · ' + clockText" :confirm-text="confirmDialog.confirmText" :pending-text="confirmDialog.pendingText" :pending="actionPending" @cancel="pendingAction = null" @confirm="runPendingAction">
      <template #icon><RotateCcw v-if="pendingAction === 'reset'" :size="18" /><Check v-else :size="18" /></template>
    </ConfirmDialog>
  </section>
</template>

<style scoped>
.focus-page{max-width:1120px;margin:0 auto;padding:18px 0 60px;color:#203d36}
.focus-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin:10px 0 20px}
.focus-intro h1{margin:4px 0 8px;font-family:Georgia,serif;font-size:40px;letter-spacing:-1px}
.focus-intro p{margin:0;max-width:620px;color:#617772;line-height:1.7}
.eyebrow{margin:0;color:#0b7771;font-size:11px;font-weight:800;letter-spacing:2px;text-transform:uppercase}
.focus-tabs{display:flex;flex:none;gap:6px;padding:5px;border:1px solid #dce8e1;border-radius:10px;background:#f6faf8}
.focus-tabs a{padding:8px 16px;border-radius:7px;color:#4a6b62;font-size:14px;font-weight:700;text-decoration:none;white-space:nowrap}
.focus-tabs a.active{background:#0b7771;color:#fff}
.loading-line{margin:0 0 12px;color:#617772;font-size:13px}
.group-card{border:1px solid #dce8e1;border-radius:14px;background:#fff;box-shadow:0 10px 25px rgba(31,75,62,.045)}
.group-alert{margin:8px 0 14px;padding:12px 15px;border-radius:8px;font-size:14px}
.group-alert.error{background:#fdf0ee;color:#a4443a}
.group-alert.success{background:#edf7f2;color:#1f6d55}
.button{display:inline-flex;align-items:center;justify-content:center;gap:6px;padding:10px 15px;border:1px solid #bfd5cb;border-radius:7px;background:#fff;color:#20584f;font-size:14px;font-weight:700;text-decoration:none;cursor:pointer}
.button.primary{background:#0b7771;border-color:#0b7771;color:#fff}
.button.ghost{background:#f1f7f4}
.button:disabled{opacity:.5;cursor:not-allowed}
.button.primary:hover:not(:disabled){background:#096660}
.text-button{padding:4px 0;border:0;background:none;color:#0b7771;font-size:13px;font-weight:700;cursor:pointer}
.text-button:disabled{color:#aabcb6;cursor:not-allowed}
.card-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:14px}
.card-head h2{display:flex;align-items:center;gap:7px;margin:0;font-size:16px;font-weight:700}
.card-head a,.card-head span{color:#617772;font-size:12px;text-decoration:none}
.card-head a:hover{color:#0b7771}
.empty-line{margin:6px 0;color:#7c918a;font-size:13px;line-height:1.7}
.muted{color:#7c918a;font-size:12px;line-height:1.7}
.muted a{color:#0b7771}
.focus-grid{display:grid;grid-template-columns:minmax(0,1.35fr) minmax(0,1fr);gap:16px;align-items:start}
.timer-card{padding:24px;display:flex;flex-direction:column;align-items:center;gap:18px}
.timer-head{display:flex;align-items:center;justify-content:space-between;gap:12px;width:100%}
.phase-pill{display:inline-flex;align-items:center;gap:6px;padding:5px 12px;border-radius:999px;background:#eef6f2;color:#1f6d55;font-size:12px;font-weight:700}
.phase-pill.break{background:#fdf5e7;color:#8c6a2a}
.timer-hint{color:#7c918a;font-size:12px;text-align:right}
.ring-wrap{position:relative;display:grid;place-items:center;width:220px;height:220px}
.ring{width:220px;height:220px}
.ring circle{fill:none;stroke-width:12;stroke-linecap:round}
.ring-track{stroke:#e9f2ed}
.ring-value{stroke:#0b7771;transition:stroke-dashoffset .25s linear}
.ring.break .ring-value{stroke:#cf9f4b}
.ring-copy{position:absolute;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px}
.ring-copy strong{font-family:Georgia,serif;font-size:44px;letter-spacing:-1px;line-height:1}
.ring-copy small{color:#7c918a;font-size:12px}
.timer-actions{display:flex;flex-wrap:wrap;justify-content:center;gap:10px}
.duration-row{display:flex;gap:18px;width:100%;justify-content:center;padding-top:16px;border-top:1px solid #eef4f0}
.duration-field{display:flex;align-items:center;gap:6px;font-size:13px;color:#4a6b62}
.duration-field span{font-weight:700}
.duration-field em{color:#7c918a;font-style:normal;font-size:12px}
.duration-field input{width:64px;padding:7px 8px;border:1px solid #dce8e1;border-radius:7px;font-size:14px;text-align:center;color:#203d36}
.duration-field input:disabled{background:#f7faf8;color:#93a8a1}
.step{display:grid;place-items:center;width:26px;height:26px;border:1px solid #dce8e1;border-radius:50%;background:#fff;color:#2f6d5f;cursor:pointer}
.step:disabled{opacity:.45;cursor:not-allowed}
.timer-note{margin:0;color:#8a9d96;font-size:12px;text-align:center;line-height:1.7}
.focus-form{padding:22px;display:flex;flex-direction:column;gap:12px}
.field{display:flex;flex-direction:column;gap:6px;color:#3f5f57;font-size:13px;font-weight:700}
.field input,.field select,.field textarea{padding:10px 12px;border:1px solid #dce8e1;border-radius:8px;background:#fff;color:#203d36;font-size:14px;font-weight:400;font-family:inherit}
.field input:focus,.field select:focus,.field textarea:focus{border-color:#8fc3b4;outline:none}
.field textarea{resize:vertical}
.choice{display:flex;align-items:center;gap:8px;color:#4a6b62;font-size:13px;font-weight:700}
.choice input{width:16px;height:16px;accent-color:#0b7771}
.today-card,.manual-card,.history-card,.chart-card{padding:22px}
.today-card{margin-top:16px}
.manual-card{margin-top:16px}
.history-card{margin-top:16px}
.stat-strip{display:grid;grid-template-columns:repeat(6,minmax(0,1fr));gap:12px}
.stat-strip div{display:flex;flex-direction:column;gap:6px;padding:12px;border-radius:10px;background:#f7fbf9}
.stat-strip span{color:#7c918a;font-size:12px}
.stat-strip b{font-size:15px}
.manual-form{display:flex;flex-direction:column;gap:12px;align-items:flex-start}
.manual-form .button{margin-top:2px}
.form-two{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;width:100%}
.manual-form .field,.manual-form .form-two{width:100%}
.range-bar{display:flex;flex-wrap:wrap;gap:8px;margin:0 0 16px}
.range-bar.small{margin:0}
.range-button{padding:8px 16px;border:1px solid #dce8e1;border-radius:999px;background:#fff;color:#4a6b62;font-size:13px;font-weight:700;cursor:pointer}
.range-button.active{background:#0b7771;border-color:#0b7771;color:#fff}
.login-card{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:22px}
.login-card p{margin:0;color:#617772;font-size:14px}
.summary-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px;margin-bottom:16px}
.summary-card{display:flex;flex-direction:column;gap:8px;padding:18px;color:#0b7771}
.summary-card span{color:#7c918a;font-size:12px;font-weight:700}
.summary-card b{color:#203d36;font-size:20px;font-family:Georgia,serif;letter-spacing:-.5px}
.chart-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;margin-bottom:16px}
.bar-chart{display:flex;align-items:flex-end;gap:5px;height:190px}
.bar-column{display:flex;flex:1;flex-direction:column;justify-content:flex-end;align-items:center;gap:4px;height:100%}
.bar-value{color:#617772;font-size:10px;font-weight:700;line-height:12px}
.bar-track,.hour-column .bar-track{display:flex;align-items:flex-end;width:100%;flex:1;min-height:0;border-radius:6px;background:#f2f7f4;overflow:hidden}
.bar-track i,.hour-column .bar-track i{display:block;width:100%;border-radius:6px;background:#0b7771}
.bar-column small,.hour-column small{color:#8a9d96;font-size:10px;line-height:14px;white-space:nowrap}
.hour-chart{display:flex;align-items:flex-end;gap:3px;height:150px}
.hour-column{display:flex;flex:1;flex-direction:column;justify-content:flex-end;align-items:center;gap:4px;height:100%}
.subject-list{display:flex;flex-direction:column;gap:10px}
.subject-row{display:grid;grid-template-columns:76px minmax(0,1fr) 108px;align-items:center;gap:10px;font-size:13px}
.subject-row b{font-weight:700;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.subject-row span{color:#7c918a;font-size:12px;text-align:right}
.subject-track{height:9px;border-radius:999px;background:#f2f7f4;overflow:hidden}
.subject-track i{display:block;height:100%;border-radius:999px;background:linear-gradient(90deg,#0b7771,#5fae95)}
.my-rank{display:flex;align-items:center;gap:10px;padding:10px 12px;margin-bottom:12px;border-radius:10px;background:#f7fbf9;font-size:13px;color:#4a6b62}
.my-rank b{color:#0b7771;font-size:14px}
.my-rank small{color:#7c918a}
.board-list{display:flex;flex-direction:column}
.board-row{display:grid;grid-template-columns:34px minmax(0,1fr) 108px 54px;align-items:center;gap:10px;padding:9px 10px;border-radius:9px;font-size:13px}
.board-row+.board-row{margin-top:2px}
.board-row.me{background:#eef6f2}
.board-rank{display:grid;place-items:center;width:24px;height:24px;border-radius:7px;background:#f2f7f4;color:#4a6b62;font-size:12px;font-weight:700}
.board-row.me .board-rank{background:#0b7771;color:#fff}
.board-row b{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.board-row span{color:#3f5f57}
.board-row small{color:#8a9d96;text-align:right}
.session-list{display:flex;flex-direction:column}
.session-row{display:grid;grid-template-columns:minmax(0,1fr) 88px 88px;align-items:center;gap:12px;padding:11px 0}
.session-row+.session-row{border-top:1px solid #f0f5f2}
.session-main{display:flex;flex-direction:column;gap:4px;min-width:0}
.session-main b{font-size:14px}
.session-main small{color:#7c918a;font-size:12px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.session-minutes{font-size:14px;text-align:right}
.source-pill{justify-self:start;padding:3px 9px;border-radius:999px;background:#eef6f2;color:#1f6d55;font-size:11px;font-weight:700}
.source-pill.manual{background:#f3f0fb;color:#5b4b9a}
.source-pill.room{background:#eaf3fb;color:#3a6f9e}
.source-pill.plan{background:#fdf5e7;color:#8c6a2a}
.pager{display:flex;align-items:center;gap:14px;justify-content:flex-end;margin-top:14px;color:#7c918a;font-size:12px}
@media (max-width:900px){
.focus-heading{align-items:flex-start;flex-direction:column;gap:14px}
.focus-grid,.chart-grid{grid-template-columns:minmax(0,1fr)}
.summary-grid{grid-template-columns:repeat(2,minmax(0,1fr))}
.stat-strip{grid-template-columns:repeat(3,minmax(0,1fr))}
}
@media (max-width:620px){
.focus-page{padding:10px 0 40px}
.focus-intro h1{font-size:30px}
.focus-tabs{width:100%;overflow:auto}
.timer-card,.focus-form,.today-card,.manual-card,.history-card,.chart-card{padding:16px}
.ring-wrap,.ring{width:180px;height:180px}
.ring-copy strong{font-size:34px}
.duration-row{flex-direction:column;gap:10px;align-items:center}
.summary-grid,.stat-strip,.form-two{grid-template-columns:minmax(0,1fr)}
.subject-row{grid-template-columns:64px minmax(0,1fr) 84px}
.session-row{grid-template-columns:minmax(0,1fr) 84px;row-gap:6px}
.session-minutes{grid-column:2}
.login-card{flex-direction:column;align-items:flex-start}
}
</style>
