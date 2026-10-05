<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, date, send, upload } from '../api'
import { useSession } from '../store'
import { Archive, CalendarDays, Check, ChevronLeft, ChevronRight, Clock, Flame, Image as ImageIcon, Plus, Trash2, Trophy } from 'lucide-vue-next'
import ConfirmDialog from './ConfirmDialog.vue'

const route = useRoute(), session = useSession()
const error = ref(''), notice = ref(''), loading = ref(false)
const isGoals = computed(() => route.path.startsWith('/checkin/goals'))
const needsLogin = computed(() => session.ready && !session.user)
const statusOptions: { value: 'active' | 'archived' | 'all'; label: string }[] = [{ value: 'active', label: '进行中' }, { value: 'archived', label: '已归档' }, { value: 'all', label: '全部' }]
const weekLabels = ['一', '二', '三', '四', '五', '六', '日']
const status = ref<'active' | 'archived' | 'all'>('active')
const settings = ref<any>({}), stats = ref<any>({}), calendar = ref<any>(null)
const goals = ref<any[]>([]), badges = ref<any[]>([]), publicGoals = ref<any[]>([]), records = ref<any[]>([]), groupOptions = ref<any[]>([])
const recordPage = ref(1), hasNext = ref(false)
const calYear = ref(new Date().getFullYear()), calMonth = ref(new Date().getMonth() + 1)
const publicSubject = ref(''), openPublicId = ref<number | null>(null)
const checkinForms = ref<Record<string, any>>({})
const form = ref({ title: '', subject: '', frequency: 'daily', targetMinutes: 0, visibility: 'private', groupId: 0, allowMakeup: true, reminderTime: '' })
const editingId = ref(0), goalToDelete = ref<any>(null), deletePending = ref(false)
let noticeTimer: number | undefined

function isoDay(value: Date) { return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}` }
const todayIso = isoDay(new Date()), makeupMin = isoDay(new Date(Date.now() - 2 * 86400000))
function flash(message: string) { notice.value = message; window.clearTimeout(noticeTimer); noticeTimer = window.setTimeout(() => { notice.value = '' }, 3000) }
// 后端的列表接口有的返回裸数组、有的返回 {items:[…]}，这里统一吃掉
function list(payload: any): any[] { return Array.isArray(payload) ? payload : payload?.items || [] }
function day(value?: string | null) { return value ? date(value) : '—' }
function frequencyLabel(value: string) { return value === 'weekly' ? '每周' : '每日' }
function visibilityLabel(value: string) { return value === 'public' ? '公开' : value === 'group' ? '小组可见' : '私密' }
function canMakeup(goal: any) { return !!goal.allow_makeup && settings.value.allow_makeup !== false }
function emptyCheckinForm(goal?: any) { return { content: '', durationMinutes: goal?.target_minutes ? Number(goal.target_minutes) : 0, imageUrl: '', date: '' } }
function formFor(goal: any) { if (!checkinForms.value[goal.id]) checkinForms.value[goal.id] = emptyCheckinForm(goal); return checkinForms.value[goal.id] }
function ensureForms() {
  const next: Record<string, any> = { ...checkinForms.value }
  for (const goal of [...goals.value, ...publicGoals.value]) if (!next[goal.id]) next[goal.id] = emptyCheckinForm(goal)
  checkinForms.value = next
}
const calendarCells = computed(() => {
  const hits = new Map<string, any>()
  for (const entry of calendar.value?.days || []) hits.set(String(entry.date).slice(0, 10), entry)
  const offset = (new Date(calYear.value, calMonth.value - 1, 1).getDay() + 6) % 7
  const total = new Date(calYear.value, calMonth.value, 0).getDate()
  const cells: any[] = []
  for (let index = 0; index < offset; index++) cells.push({ key: 'gap-' + index, day: 0 })
  for (let dayNumber = 1; dayNumber <= total; dayNumber++) {
    const iso = `${calYear.value}-${String(calMonth.value).padStart(2, '0')}-${String(dayNumber).padStart(2, '0')}`
    const hit = hits.get(iso)
    cells.push({ key: iso, day: dayNumber, count: Number(hit?.count) || 0, minutes: Number(hit?.minutes) || 0 })
  }
  return cells
})
const monthChecked = computed(() => { const value = calendar.value?.checkedDays; return Array.isArray(value) ? value.length : Number(value) || 0 })

async function loadRecords(page: number) {
  const data = await api<any>('/checkin/records?page=' + page)
  records.value = data.items || []; hasNext.value = !!data.hasNext; recordPage.value = Number(data.page) || page
}
async function loadCalendar(year: number, month: number) {
  calendar.value = await api<any>('/checkin/calendar?year=' + year + '&month=' + month)
  calYear.value = year; calMonth.value = month
}
async function loadPublic() {
  const subject = publicSubject.value.trim()
  publicGoals.value = list(await api<any>('/checkin/public' + (subject ? '?' + new URLSearchParams({ subject }) : '')))
  ensureForms()
}
async function load() {
  if (needsLogin.value) return
  loading.value = true; error.value = ''
  try {
    if (isGoals.value) {
      const [data, config] = await Promise.all([api<any>('/checkin/goals?status=' + status.value), api<any>('/checkin/settings')])
      goals.value = list(data); settings.value = config
      try { groupOptions.value = list(await api<any>('/groups?scope=mine')) } catch { groupOptions.value = [] }
      if (!editingId.value) form.value.allowMakeup = config.allow_makeup !== false
      ensureForms()
      return
    }
    const subject = publicSubject.value.trim()
    const overview = await Promise.all([api<any>('/checkin/settings'), api<any>('/checkin/stats'), api<any>('/checkin/goals?status=active'), api<any>('/checkin/badges'), api<any>('/checkin/public' + (subject ? '?' + new URLSearchParams({ subject }) : ''))])
    settings.value = overview[0]; stats.value = overview[1]; goals.value = list(overview[2]); badges.value = list(overview[3]); publicGoals.value = list(overview[4])
    await Promise.all([loadRecords(1), loadCalendar(calYear.value, calMonth.value)])
    ensureForms()
  } catch (e) { error.value = (e as Error).message } finally { loading.value = false }
}
async function run(task: () => Promise<unknown>) {
  try { error.value = ''; await task(); await load(); return true }
  catch (e) { error.value = (e as Error).message; return false }
}
async function setStatus(value: 'active' | 'archived' | 'all') { if (status.value === value) return; status.value = value; await load() }
async function searchPublic() { try { error.value = ''; await loadPublic() } catch (e) { error.value = (e as Error).message } }
async function shiftMonth(step: number) {
  const target = new Date(calYear.value, calMonth.value - 1 + step, 1)
  try { error.value = ''; await loadCalendar(target.getFullYear(), target.getMonth() + 1) } catch (e) { error.value = (e as Error).message }
}
async function turnRecordPage(step: number) { try { error.value = ''; await loadRecords(recordPage.value + step) } catch (e) { error.value = (e as Error).message } }
async function pickImage(event: Event, goal: any) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  if (!file) return
  try { error.value = ''; const result = await upload(file); formFor(goal).imageUrl = result.path; flash('图片已上传') }
  catch (e) { error.value = (e as Error).message }
  finally { input.value = '' }
}
async function submitCheckin(goal: any) {
  const draft = formFor(goal)
  const payload: any = { goalId: goal.id, content: draft.content, durationMinutes: Number(draft.durationMinutes) || 0, subject: goal.subject || '', imageUrl: draft.imageUrl, visibility: goal.visibility === 'public' ? 'public' : 'private' }
  if (draft.date) payload.date = draft.date
  return await run(async () => {
    const result = await send<any>('/checkin', 'POST', payload)
    Object.assign(draft, emptyCheckinForm(goal))
    flash(result?.streak ? `打卡成功，已连续 ${result.streak} 天` : '打卡成功')
  })
}
async function checkinPublic(goal: any) { if (await submitCheckin(goal)) openPublicId.value = null }
function resetForm() {
  form.value = { title: '', subject: '', frequency: 'daily', targetMinutes: 0, visibility: 'private', groupId: groupOptions.value[0]?.id || 0, allowMakeup: settings.value.allow_makeup !== false, reminderTime: '' }
  editingId.value = 0
}
function startEdit(goal: any) {
  editingId.value = goal.id
  form.value = { title: goal.title, subject: goal.subject || '', frequency: goal.frequency || 'daily', targetMinutes: Number(goal.target_minutes) || 0, visibility: goal.visibility || 'private', groupId: Number(goal.group_id) || 0, allowMakeup: goal.allow_makeup !== false, reminderTime: goal.reminder_time || '' }
  window.scrollTo({ top: 0, behavior: 'smooth' })
}
async function saveGoal() {
  const draft = form.value, title = draft.title.trim()
  if (title.length < 2 || title.length > 60) { error.value = '目标标题需要 2–60 个字符'; return }
  if (draft.visibility === 'group' && !Number(draft.groupId)) { error.value = '请选择这个打卡目标所属的小组'; return }
  const payload: any = { title, subject: draft.subject, frequency: draft.frequency, targetMinutes: Number(draft.targetMinutes) || 0, visibility: draft.visibility, allowMakeup: draft.allowMakeup, reminderTime: draft.reminderTime }
  if (draft.visibility === 'group') payload.groupId = Number(draft.groupId)
  const editing = editingId.value
  await run(async () => {
    if (editing) await send('/checkin/goals/' + editing, 'PATCH', payload)
    else await send('/checkin/goals', 'POST', payload)
    flash(editing ? '打卡目标已更新' : '打卡目标已创建')
    resetForm()
  })
}
async function toggleArchive(goal: any) {
  const archiving = goal.status !== 'archived'
  await run(async () => { await send('/checkin/goals/' + goal.id + '/archive', 'PATCH', { archived: archiving }); flash(archiving ? '目标已归档' : '已取消归档') })
}
async function confirmDelete() {
  if (!goalToDelete.value || deletePending.value) return
  deletePending.value = true
  try {
    error.value = ''
    await send('/checkin/goals/' + goalToDelete.value.id, 'DELETE')
    goalToDelete.value = null
    flash('打卡目标已删除')
    await load()
  } catch (e) { error.value = (e as Error).message }
  finally { deletePending.value = false }
}
async function start() { if (!session.ready) await session.refresh(); await load() }
onMounted(start)
watch(() => route.path, load)
</script>

<template>
  <section class="checkin-page">
    <div v-if="error" class="group-alert error">{{ error }}</div>
    <div v-if="notice" class="group-alert success">{{ notice }}</div>
    <p v-if="loading" class="muted loading-line">加载中…</p>

    <div v-if="needsLogin" class="group-card login-gate">
      <h1>学习打卡</h1>
      <p>登录后即可创建打卡目标、记录每天的学习时长，并查看连续天数、积分与成就徽章。</p>
      <RouterLink class="button primary" :to="{ path: '/login', query: { next: route.path } }">去登录</RouterLink>
    </div>

    <template v-else-if="isGoals">
      <div class="group-heading">
        <div>
          <p class="eyebrow">CHECK-IN GOALS</p>
          <h1>打卡目标</h1>
          <p>把想坚持的事拆成每日或每周的小目标，打卡会累计积分、连续天数并点亮徽章。</p>
        </div>
        <RouterLink class="button ghost" to="/checkin">返回打卡首页</RouterLink>
      </div>

      <form class="group-card goal-form" @submit.prevent="saveGoal">
        <div class="form-head">
          <h2>{{ editingId ? '编辑打卡目标' : '新建打卡目标' }}</h2>
          <button v-if="editingId" class="text-button" type="button" @click="resetForm">取消编辑</button>
        </div>
        <p class="muted">每次打卡可获得 {{ settings.checkin_points || 0 }} 积分，连续打卡满 {{ settings.streak_bonus_days || 0 }} 天额外 +{{ settings.streak_bonus || 0 }} 积分。</p>
        <label>目标标题<input v-model="form.title" required minlength="2" maxlength="60" placeholder="例如：每天背 50 个单词" /></label>
        <div class="form-two">
          <label>学科<input v-model="form.subject" placeholder="英语 / 数学 / 编程" /></label>
          <label>频率<select v-model="form.frequency"><option value="daily">每日</option><option value="weekly">每周</option></select></label>
          <label>目标时长（分钟）<input v-model.number="form.targetMinutes" type="number" min="0" max="1440" /></label>
          <label>提醒时间<input v-model="form.reminderTime" type="time" /></label>
        </div>
        <fieldset>
          <legend>可见性</legend>
          <label class="choice"><input v-model="form.visibility" type="radio" value="private" />私密：仅自己可见</label>
          <label class="choice"><input v-model="form.visibility" type="radio" value="public" />公开：出现在公开目标发现里</label>
          <label class="choice"><input v-model="form.visibility" type="radio" value="group" />小组可见</label>
        </fieldset>
        <label v-if="form.visibility === 'group'">所属小组
          <select v-if="groupOptions.length" v-model.number="form.groupId">
            <option :value="0">请选择小组</option>
            <option v-for="option in groupOptions" :key="option.id" :value="option.id">{{ option.name }}</option>
          </select>
          <input v-else v-model.number="form.groupId" type="number" min="1" placeholder="输入小组 ID" />
        </label>
        <label class="choice"><input v-model="form.allowMakeup" type="checkbox" />允许补卡（最近 2 天内漏打的可补）</label>
        <div>
          <button class="button primary" type="submit">{{ editingId ? '保存修改' : '创建目标' }}</button>
        </div>
      </form>

      <div class="status-switch">
        <button v-for="option in statusOptions" :key="option.value" class="button"
          :class="status === option.value ? 'primary' : 'ghost'" type="button"
          @click="setStatus(option.value)">{{ option.label }}</button>
      </div>

      <div class="goal-list">
        <article v-for="goal in goals" :key="goal.id"
          class="group-card goal-card" :class="goal.status === 'archived' ? 'archived' : ''">
          <div class="goal-top">
            <div>
              <div class="goal-badges">
                <span class="chip">{{ frequencyLabel(goal.frequency) }}</span>
                <span v-if="goal.subject" class="chip">{{ goal.subject }}</span>
                <span class="chip">{{ visibilityLabel(goal.visibility) }}</span>
                <span v-if="goal.status === 'archived'" class="chip muted-chip">已归档</span>
              </div>
              <h2>{{ goal.title }}</h2>
              <p class="goal-meta">
                <span v-if="goal.target_minutes > 0">目标 {{ goal.target_minutes }} 分钟</span>
                <span v-if="goal.reminder_time">提醒 {{ goal.reminder_time }}</span>
                <span>已打卡 {{ goal.checkin_count }} 次</span>
                <span>连续 {{ goal.streak }} 天</span>
                <span>上次 {{ day(goal.last_checkin) }}</span>
                <span v-if="goal.allow_makeup">允许补卡</span>
                <span v-if="goal.checkedToday">今日已打卡</span>
              </p>
            </div>
            <div class="goal-actions">
              <button class="button ghost" type="button" @click="startEdit(goal)">编辑</button>
              <button class="button ghost" type="button" @click="toggleArchive(goal)">
                <Archive :size="14" /> {{ goal.status === 'archived' ? '取消归档' : '归档' }}
              </button>
              <button class="button danger" type="button" @click="goalToDelete = goal">
                <Trash2 :size="14" /> 删除
              </button>
            </div>
          </div>
        </article>
        <div v-if="!goals.length" class="empty-state group-card">这里还没有打卡目标，用上面的表单创建第一个吧。</div>
      </div>

      <ConfirmDialog :open="!!goalToDelete" title="删除打卡目标" :subject="goalToDelete?.title"
        description="目标的打卡记录会一并移除，积分与连续天数不会回滚，删除后无法恢复。" confirm-text="确认删除"
        pending-text="删除中…" :pending="deletePending" @cancel="goalToDelete = null" @confirm="confirmDelete">
        <template #icon><Trash2 :size="18" /></template>
      </ConfirmDialog>
    </template>

    <template v-else>
      <div class="group-heading">
        <div>
          <p class="eyebrow">DAILY STUDY CHECK-IN</p>
          <h1>学习打卡</h1>
          <p>每天一点专注，把坚持变成看得见的记录：打卡、补卡、连续天数、时长与积分都在这里。</p>
        </div>
        <RouterLink class="button primary" to="/checkin/goals"><Plus :size="16" /> 新建打卡目标</RouterLink>
      </div>

      <div class="stat-row">
        <div class="group-card stat-card"><CalendarDays :size="18" /><b>{{ stats.today || 0 }}</b><span>今日打卡</span></div>
        <div class="group-card stat-card"><Flame :size="18" /><b>{{ stats.streak || 0 }} 天</b><span>连续打卡 · 最长 {{ stats.longest || 0 }} 天</span></div>
        <div class="group-card stat-card"><Trophy :size="18" /><b>{{ stats.days || 0 }} 天</b><span>累计打卡天数</span></div>
        <div class="group-card stat-card"><Clock :size="18" /><b>{{ stats.minutes || 0 }} 分钟</b><span>累计学习时长</span></div>
        <div class="group-card stat-card"><Check :size="18" /><b>{{ stats.points || 0 }}</b><span>打卡积分</span></div>
      </div>
      <p class="muted stat-note">进行中的目标 {{ stats.activeGoals || 0 }} 个 · 已补卡 {{ stats.makeup || 0 }} 次 · 补卡需目标与平台都允许，且距今不超过 2 天。</p>

      <div class="group-card calendar-card">
        <div class="panel-heading">
          <div>
            <h2>打卡日历</h2>
            <p>有打卡的日期会高亮，悬停可以看到当天的次数与时长。</p>
          </div>
          <div class="cal-nav">
            <button class="button ghost" type="button" aria-label="上个月" @click="shiftMonth(-1)"><ChevronLeft :size="16" /></button>
            <b>{{ calYear }} 年 {{ calMonth }} 月</b>
            <button class="button ghost" type="button" aria-label="下个月" @click="shiftMonth(1)"><ChevronRight :size="16" /></button>
          </div>
        </div>
        <div class="cal-grid">
          <span v-for="label in weekLabels" :key="label" class="cal-week">{{ label }}</span>
          <span v-for="cell in calendarCells" :key="cell.key"
            :class="['cal-cell', cell.day ? (cell.count ? 'hit' : '') : 'gap']"
            :title="cell.count ? `${cell.count} 次打卡 · ${cell.minutes} 分钟` : undefined">{{ cell.day || '' }}</span>
        </div>
        <p class="cal-summary">当前连续 <b>{{ calendar?.streak ?? stats.streak ?? 0 }}</b> 天 · 本月打卡
          <b>{{ monthChecked }}</b> 天 · 本月学习 <b>{{ calendar?.minutes || 0 }}</b> 分钟<span
            v-if="calendar?.todayChecked"> · 今天已打卡 ✓</span>
        </p>
      </div>

      <h2 class="section-title">今日目标</h2>
      <div class="goal-list">
        <article v-for="goal in goals" :key="goal.id" class="group-card goal-card">
          <div class="goal-top">
            <div>
              <div class="goal-badges">
                <span class="chip">{{ frequencyLabel(goal.frequency) }}</span>
                <span v-if="goal.subject" class="chip">{{ goal.subject }}</span>
                <span v-if="goal.target_minutes > 0" class="chip">目标 {{ goal.target_minutes }} 分钟</span>
              </div>
              <h3>{{ goal.title }}</h3>
              <p class="goal-meta">
                <span>连续 {{ goal.streak }} 天</span>
                <span>已打卡 {{ goal.checkin_count }} 次</span>
                <span>上次 {{ day(goal.last_checkin) }}</span>
              </p>
              <p v-if="goal.pendingReminder && !goal.checkedToday" class="reminder">今天还没打卡，别忘了。</p>
            </div>
            <div v-if="goal.checkedToday" class="done-badge"><Check :size="14" /> 今日已打卡
              <template v-if="goal.today_minutes != null">· 今日 {{ goal.today_minutes }} 分钟</template>
              <template v-else>· 今日 {{ goal.today_count || 0 }} 次</template>
            </div>
          </div>
          <form v-if="!goal.checkedToday" class="checkin-form" @submit.prevent="submitCheckin(goal)">
            <textarea v-model="formFor(goal).content" rows="2" placeholder="今天学了什么？记一句进度或感受"></textarea>
            <div class="checkin-row">
              <label>时长（分钟）<input v-model.number="formFor(goal).durationMinutes" type="number" min="0" max="1440" /></label>
              <label class="upload-label"><ImageIcon :size="15" /> 添加图片<input type="file" accept="image/*" @change="pickImage($event, goal)" /></label>
              <label v-if="canMakeup(goal)">补卡日期<input v-model="formFor(goal).date" type="date" :min="makeupMin" :max="todayIso" /></label>
              <button class="button primary" type="submit">打卡</button>
            </div>
            <img v-if="formFor(goal).imageUrl" :src="formFor(goal).imageUrl" alt="打卡图片预览" class="thumb" />
          </form>
        </article>
        <div v-if="!goals.length" class="empty-state group-card">还没有进行中的打卡目标，先去
          <RouterLink to="/checkin/goals">创建目标</RouterLink> 吧。
        </div>
      </div>

      <div class="group-card panel-card">
        <div class="panel-heading">
          <div>
            <h2>成就徽章</h2>
            <p>连续打卡与累计时长会解锁徽章，已获得的会高亮显示。</p>
          </div>
        </div>
        <div class="badge-list">
          <div v-for="badge in badges" :key="badge.code" class="badge" :class="badge.earned ? 'earned' : 'locked'">
            <Trophy :size="16" />
            <b>{{ badge.name }}</b>
            <small>{{ badge.detail }}</small>
            <em>{{ badge.earned ? (badge.earned_at ? '获得于 ' + date(badge.earned_at) : '已获得') : '未获得' }}</em>
          </div>
          <p v-if="!badges.length" class="muted">暂时还没有徽章数据。</p>
        </div>
      </div>

      <div class="group-card panel-card">
        <div class="panel-heading">
          <div>
            <h2>公开目标发现</h2>
            <p>看看别人在坚持什么，围观后可以直接为同一个目标打卡。</p>
          </div>
          <form class="inline-form public-search" @submit.prevent="searchPublic">
            <input v-model="publicSubject" placeholder="按学科筛选，如：英语" />
            <button class="button" type="submit">筛选</button>
          </form>
        </div>
        <div class="goal-list">
          <article v-for="goal in publicGoals" :key="goal.id" class="public-card">
            <div class="public-head">
              <div>
                <h3>{{ goal.title }}</h3>
                <p class="goal-meta">
                  <span v-if="goal.subject">{{ goal.subject }}</span>
                  <span v-if="goal.username || goal.owner_username">{{ goal.username || goal.owner_username }}</span>
                  <span>已打卡 {{ goal.checkin_count || 0 }} 次</span>
                  <span>连续 {{ goal.streak || 0 }} 天</span>
                </p>
              </div>
              <button v-if="openPublicId !== goal.id" class="button ghost" type="button"
                @click="openPublicId = goal.id">为它打卡</button>
            </div>
            <form v-if="openPublicId === goal.id" class="checkin-form compact" @submit.prevent="checkinPublic(goal)">
              <textarea v-model="formFor(goal).content" rows="2" placeholder="写下你的打卡内容"></textarea>
              <div class="checkin-row">
                <label>时长（分钟）<input v-model.number="formFor(goal).durationMinutes" type="number" min="0" max="1440" /></label>
                <button class="button primary" type="submit">打卡</button>
                <button class="button ghost" type="button" @click="openPublicId = null">取消</button>
              </div>
            </form>
          </article>
          <p v-if="!publicGoals.length" class="muted">没有找到公开的打卡目标，换个学科再试试。</p>
        </div>
      </div>

      <div class="group-card panel-card">
        <div class="panel-heading">
          <div>
            <h2>我的打卡记录</h2>
            <p>最近的学习记录，补卡会标注出来。</p>
          </div>
          <div class="cal-nav">
            <button class="button ghost" type="button" :disabled="recordPage <= 1" @click="turnRecordPage(-1)">上一页</button>
            <b>第 {{ recordPage }} 页</b>
            <button class="button ghost" type="button" :disabled="!hasNext" @click="turnRecordPage(1)">下一页</button>
          </div>
        </div>
        <div class="record-list">
          <div v-for="record in records" :key="record.id" class="record-row">
            <div class="record-main">
              <b>{{ record.goal_title || record.title || '打卡记录' }}</b>
              <p v-if="record.content">{{ record.content }}</p>
              <small>{{ day(record.checkin_date || record.created_at) }}<span v-if="record.duration_minutes"> · {{ record.duration_minutes }} 分钟</span><span v-if="record.subject"> · {{ record.subject }}</span></small>
            </div>
            <div class="record-tags">
              <span v-if="record.make_up" class="chip muted-chip">补卡</span>
              <span v-if="record.points" class="chip">+{{ record.points }} 积分</span>
              <img v-if="record.image_url" :src="record.image_url" alt="打卡图片" class="thumb small" />
            </div>
          </div>
          <p v-if="!records.length" class="muted">还没有打卡记录，从今天开始吧。</p>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.checkin-page{max-width:1120px;margin:0 auto;padding:18px 0 60px;color:#203d36}
.group-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin:10px 0 24px}
.group-heading h1{margin:3px 0 8px;font-family:Georgia,serif;font-size:42px;letter-spacing:-1px}
.group-heading p{color:#617772;max-width:650px;line-height:1.7}
.eyebrow{margin:0;color:#0b7771!important;font-size:11px!important;font-weight:800;letter-spacing:1.8px;text-transform:uppercase}
.button{display:inline-flex;align-items:center;justify-content:center;gap:6px;border:1px solid #bfd5cb;border-radius:7px;padding:10px 15px;background:#fff;color:#20584f;cursor:pointer;font-weight:700;text-decoration:none}
.button:disabled{opacity:.5;cursor:not-allowed}
.button.primary{background:#0b7771;border-color:#0b7771;color:#fff}
.button.ghost{background:#eff7f3}
.button.danger{background:#c94d43;border-color:#c94d43;color:#fff}
.button.danger:hover{background:#ad3d35;border-color:#ad3d35}
.group-card{border:1px solid #dce8e1;border-radius:14px;background:#fff;box-shadow:0 10px 25px rgba(31,75,62,.045)}
.group-alert{margin:8px 0 14px;padding:12px 15px;border-radius:8px}
.group-alert.error{background:#fff2f0;color:#9e463c}
.group-alert.success{background:#edf8f2;color:#237358}
.muted{color:#82968e;font-size:13px}
.loading-line{margin:0 0 10px}
.login-gate{display:flex;flex-direction:column;align-items:center;gap:12px;padding:30px;text-align:center}
.login-gate h1{margin:0;font-family:Georgia,serif;font-size:32px}
.login-gate p{margin:0;max-width:520px;color:#617772;line-height:1.7}
.stat-row{display:grid;grid-template-columns:repeat(5,1fr);gap:12px}
.stat-card{display:flex;flex-direction:column;gap:4px;padding:16px 18px}
.stat-card svg{color:#0b7771}
.stat-card b{font-family:Georgia,serif;font-size:24px}
.stat-card span{color:#617772;font-size:12px}
.stat-note{margin:12px 0 18px}
.calendar-card,.panel-card{padding:18px 20px;margin-bottom:18px}
.panel-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:16px;flex-wrap:wrap;padding-bottom:14px}
.panel-heading h2{margin:0;font-size:19px}
.panel-heading p{margin:4px 0 0;color:#617772;font-size:13px}
.cal-nav{display:flex;align-items:center;gap:8px}
.cal-nav b{min-width:92px;text-align:center;font-size:13px}
.cal-nav .button{padding:7px 11px;font-size:13px}
.cal-grid{display:grid;grid-template-columns:repeat(7,1fr);gap:6px}
.cal-week{text-align:center;color:#82968e;font-size:12px;font-weight:700}
.cal-cell{display:grid;place-items:center;height:38px;border:1px solid #eaf2ee;border-radius:8px;background:#fbfdfc;color:#3c534d;font-size:13px}
.cal-cell.hit{border-color:#0b7771;background:#0b7771;color:#fff;font-weight:700}
.cal-cell.gap{border-color:transparent;background:transparent}
.cal-summary{margin:14px 0 0;color:#617772;font-size:13px}
.cal-summary b{color:#0b7771}
.section-title{margin:22px 0 12px;font-size:20px}
.goal-list{display:flex;flex-direction:column;gap:12px}
.goal-card{padding:18px 20px}
.goal-card.archived{background:#fbfdfc;opacity:.65}
.goal-top{display:flex;align-items:flex-start;justify-content:space-between;gap:18px}
.goal-top h2,.goal-top h3{margin:8px 0 6px;font-size:18px}
.goal-badges{display:flex;flex-wrap:wrap;gap:6px}
.chip{border-radius:999px;padding:3px 10px;background:#eff7f3;color:#237358;font-size:12px;font-weight:700}
.muted-chip{background:#f1f3f2;color:#7d8b86}
.goal-meta{display:flex;flex-wrap:wrap;gap:12px;margin:0;color:#617772;font-size:13px}
.goal-actions{display:flex;flex-wrap:wrap;gap:8px}
.goal-actions .button{padding:8px 12px;font-size:13px}
.reminder{display:inline-block;margin:9px 0 0;padding:6px 10px;border-radius:7px;background:#fdf6e8;color:#9a6520;font-size:13px}
.done-badge{display:inline-flex;align-items:center;gap:6px;padding:8px 12px;border-radius:999px;background:#edf8f2;color:#237358;font-size:13px;font-weight:700;white-space:nowrap}
.checkin-form{display:flex;flex-direction:column;gap:10px;margin-top:14px;padding-top:14px;border-top:1px solid #e7efea}
.checkin-form.compact{margin-top:4px}
.checkin-form textarea,.public-search input,.goal-form input,.goal-form select{width:100%;box-sizing:border-box;border:1px solid #d4e1da;border-radius:7px;padding:10px 12px;background:#fbfdfc;color:#263e39}
.checkin-form textarea{font-family:inherit;resize:vertical}
.checkin-row{display:flex;align-items:flex-end;flex-wrap:wrap;gap:10px}
.checkin-row label{flex:1;min-width:120px;color:#617772;font-size:13px}
.checkin-row input{width:100%;box-sizing:border-box;border:1px solid #d4e1da;border-radius:7px;padding:9px 11px;background:#fbfdfc;color:#263e39}
.checkin-row .button{flex:none}
.upload-label{display:inline-flex!important;flex:none!important;align-items:center;gap:6px;border:1px solid #bfd5cb;border-radius:7px;padding:9px 12px;background:#eff7f3;color:#20584f!important;font-size:13px;font-weight:700;cursor:pointer}
.upload-label input{display:none}
.thumb{margin-top:4px;max-width:220px;border:1px solid #dce8e1;border-radius:10px}
.thumb.small{margin:0;max-width:64px}
.badge-list{display:grid;grid-template-columns:repeat(4,1fr);gap:12px}
.badge{display:flex;flex-direction:column;gap:4px;padding:14px;border:1px solid #dce8e1;border-radius:12px;background:#fbfdfc;color:#617772}
.badge b{color:#203d36;font-size:14px}
.badge small{font-size:12px;line-height:1.6}
.badge em{color:#82968e;font-size:12px;font-style:normal}
.badge svg{color:#b3bfba}
.badge.earned{border-color:#bfe0d2;background:#f0faf5}
.badge.earned svg,.badge.earned b,.badge.earned em{color:#0b7771}
.public-card{display:flex;flex-direction:column;gap:10px;padding:14px;border:1px solid #eaf2ee;border-radius:12px;background:#fbfdfc}
.public-head{display:flex;align-items:flex-start;justify-content:space-between;gap:14px}
.public-head h3{margin:0 0 6px;font-size:16px}
.inline-form{display:flex;align-items:center;flex-wrap:wrap;gap:8px}
.public-search input{min-width:190px;width:auto}
.record-list{display:flex;flex-direction:column}
.record-row{display:flex;align-items:flex-start;justify-content:space-between;gap:14px;padding:12px 0;border-top:1px solid #e7efea}
.record-row:first-child{border-top:0}
.record-main b{font-size:14px}
.record-main p{margin:4px 0;color:#3c534d;font-size:13px;line-height:1.6}
.record-main small{color:#617772;font-size:12px}
.record-tags{display:flex;align-items:center;gap:6px}
.goal-form{display:flex;flex-direction:column;gap:12px;padding:20px;margin-bottom:18px}
.goal-form label{display:flex;flex-direction:column;gap:6px;color:#617772;font-size:13px;font-weight:700}
.goal-form label.choice{flex-direction:row;align-items:center;gap:8px;color:#3c534d;font-weight:600}
.goal-form fieldset{border:1px solid #e3ece7;border-radius:10px;padding:12px 14px}
.goal-form legend{padding:0 6px;color:#0b7771;font-size:12px;font-weight:800;letter-spacing:1px}
.form-head{display:flex;align-items:center;justify-content:space-between;gap:12px}
.form-head h2{margin:0;font-size:19px}
.form-two{display:grid;grid-template-columns:1fr 1fr;gap:12px}
.text-button{border:0;background:none;padding:0;color:#0b7771;font-size:13px;font-weight:700;cursor:pointer}
.status-switch{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:14px}
.empty-state{padding:22px;text-align:center;color:#617772}
@media(max-width:900px){.stat-row{grid-template-columns:repeat(3,1fr)}.badge-list{grid-template-columns:repeat(2,1fr)}}
@media(max-width:620px){.checkin-page{padding:10px}.group-heading{align-items:flex-start;flex-direction:column}.group-heading h1{font-size:32px}.stat-row,.form-two,.badge-list{grid-template-columns:1fr}.goal-top,.public-head,.record-row{flex-direction:column}.goal-actions{width:100%}.goal-actions .button{flex:1}.cal-cell{height:32px;font-size:12px}.calendar-card,.panel-card{padding:14px}.public-search input{min-width:0;width:100%}}
</style>
