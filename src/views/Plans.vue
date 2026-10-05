<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, send } from '../api'
import { useSession } from '../store'
import { ElMessage } from 'element-plus'
import { Archive, BookmarkPlus, CalendarDays, Plus, Target, Trash2 } from 'lucide-vue-next'
import ConfirmDialog from './ConfirmDialog.vue'

const route = useRoute(), router = useRouter(), session = useSession()
const error = ref(''), notice = ref(''), loading = ref(false)
const pending = ref(''), pendingAction = ref(false)
const plans = ref<any[]>([]), stats = ref<any>({}), subjects = ref<string[]>([])
const detail = ref<any>(null), templates = ref<any[]>([]), groups = ref<any[]>([])
const current = ref('active'), subjectFilter = ref(''), templateSubject = ref('')
const formOpen = ref(false), editing = ref(false), formSubmitting = ref(false)
const taskSubmitting = ref(false), generating = ref(false), applying = ref(false)
const templateOpen = ref(false), templateSubmitting = ref(false), templateFromPlanOpen = ref(false), templateFromPlanSubmitting = ref(false)
const expander = ref(0)
const form = ref({ title: '', subject: '', goal: '', startDate: '', endDate: '', groupId: 0 })
const taskForm = ref({ title: '', dueDate: '', parentId: 0 })
const quickForm = ref({ title: '', days: 7, startDate: '' })
const templateFromPlanForm = ref({ title: '', cycleDays: 7 })
const templateForm = ref({ title: '', subject: '', goal: '', cycleDays: 7, tasks: '' })
const applyForm = ref({ templateId: 0, title: '', startDate: '', groupId: 0 })

const isTemplates = computed(() => route.path === '/plans/templates')
const isDetail = computed(() => !isTemplates.value && /^\/plans\/\d+$/.test(route.path))
const planId = computed(() => Number(route.params.id))
const plan = computed(() => detail.value?.plan || detail.value)
const progress = computed(() => detail.value?.progress || { total: 0, done: 0, percent: 0, doneToday: 0, overdue: 0 })
const rootTasks = computed<any[]>(() => (detail.value?.tasks || []).filter((t: any) => t.parent_id == null))
const childrenOf = computed<Record<number, any[]>>(() => { const map: Record<number, any[]> = {}; for (const t of detail.value?.tasks || []) if (t.parent_id != null) (map[t.parent_id] ||= []).push(t); return map })
const statusText = computed(() => (status: string) => status === 'completed' ? '已完成' : status === 'archived' ? '已归档' : '进行中')
const confirmConfig = computed(() => {
  if (pending.value === 'delete-plan') return { icon: 'plan', title: '删除这份学习计划', subject: plan.value?.title, description: '计划下的所有任务和完成记录都会一并移除，且无法恢复。', confirmText: '确认删除', pendingText: '删除中…' }
  if (pending.value === 'delete-task') return { icon: 'task', title: '删除这个任务', subject: pendingTask.value?.title, description: '删除后该任务的完成记录会从这份计划的进度中移除。', confirmText: '确认删除', pendingText: '删除中…' }
  return { icon: 'template', title: '删除这个模板', subject: pendingTemplate.value?.title, description: '删除后其他同学也无法再使用这个模板，且无法恢复。', confirmText: '确认删除', pendingText: '删除中…' }
})
const pendingTask = ref<any>(null), pendingTemplate = ref<any>(null)
const today = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }
const plannedStart = () => form.value.startDate || today()
function showNotice(message: string) { notice.value = message; if (expander.value) window.clearTimeout(expander.value); expander.value = window.setTimeout(() => { notice.value = '' }, 3000) }
function fmt(value?: string | null) { if (!value) return ''; const part = String(value).slice(0, 10); if (!/^\d{4}-\d{2}-\d{2}$/.test(part)) return String(value); const [y, m, d] = part.split('-').map(Number); return `${y}年${m}月${d}日` }
function period(p: any) { return `${fmt(p?.start_date) || '未设置'} ~ ${fmt(p?.end_date) || '未设置'}` }
function isOverdue(task: any) { return task.status !== 'done' && !!task.due_date && String(task.due_date).slice(0, 10) < today() }
function childrenDone(task: any) { return (childrenOf.value[task.id] || []).filter((c: any) => c.status === 'done').length }
function parentDone(task: any) { const kids = childrenOf.value[task.id] || []; return !!kids.length && kids.every((c: any) => c.status === 'done') }
function lastChildDone(task: any) { const kids = (childrenOf.value[task.id] || []).filter((c: any) => c.completed_at).map((c: any) => String(c.completed_at)); return kids.length ? kids.sort().slice(-1)[0] : task.completed_at }
function taskLines(t: any) { return String(t?.tasks || '').split('\n').map((line: string) => line.trim()).filter(Boolean) }
function ownerName(t: any) { return t?.owner_username || t?.owner_name || '平台内置' }
function offsetDate(base: string, days: number) { const [y, m, d] = base.split('-').map(Number); const dt = new Date(y, m - 1, d + days); return `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}-${String(dt.getDate()).padStart(2, '0')}` }
function resetForm() { form.value = { title: '', subject: subjectFilter.value || '', goal: '', startDate: today(), endDate: '', groupId: 0 }; editing.value = false }
function openCreate() { resetForm(); formOpen.value = true; editing.value = false }
function cancelForm() { formOpen.value = false; editing.value = false; resetForm() }
function startEdit() { const p = plan.value; if (!p) return; form.value = { title: p.title || '', subject: p.subject || '', goal: p.goal || '', startDate: String(p.start_date || '').slice(0, 10), endDate: String(p.end_date || '').slice(0, 10), groupId: p.group_id || 0 }; formOpen.value = true; editing.value = true }

async function loadGroups() {
  if (!session.user || groups.value.length) return
  try {
    const data = await api<any>('/groups?scope=mine')
    let items: any[] = Array.isArray(data) ? data : (data?.items || [])
    const mine = items.filter((g: any) => g?.id && (g.owner_id === session.user?.id || !('owner_id' in g)))
    groups.value = mine.length ? mine : items
  } catch { groups.value = [] }
}
async function load() {
  loading.value = true; error.value = ''
  try {
    if (isTemplates.value) {
      const data = await api<any>('/plans/templates?' + new URLSearchParams({ subject: templateSubject.value }))
      templates.value = [...(data.official || []), ...(data.mine || []), ...(data.shared || [])]
      subjects.value = data.subjects || []
      await loadGroups()
      return
    }
    if (isDetail.value) {
      detail.value = await api('/plans/' + planId.value)
      await loadGroups()
      return
    }
    const data = await api<any>('/plans?' + new URLSearchParams({ status: current.value, subject: subjectFilter.value }))
    plans.value = data.items || []
    stats.value = data.stats || {}
    subjects.value = data.subjects || []
  } catch (e) { error.value = (e as Error).message } finally { loading.value = false }
}
function run(task: () => Promise<unknown>) { return task().then(() => load()).catch(e => { error.value = (e as Error).message }) }
function applySubject() { load() }

async function createPlan() {
  if (formSubmitting.value) return
  const value = form.value
  if (!value.title.trim()) { error.value = '请填写计划标题'; return }
  if (value.endDate && value.startDate && value.endDate < value.startDate) { error.value = '结束日期不能早于开始日期'; return }
  formSubmitting.value = true; error.value = ''
  try {
    const body: any = { title: value.title.trim(), subject: value.subject.trim(), goal: value.goal.trim(), startDate: plannedStart() }
    if (value.endDate) body.endDate = value.endDate
    if (value.groupId) body.groupId = value.groupId
    const result = await send<any>('/plans', 'POST', body)
    formOpen.value = false; form.value = { title: '', subject: '', goal: '', startDate: '', endDate: '', groupId: 0 }
    ElMessage({ type: 'success', message: '计划已创建' })
    await router.push('/plans/' + (result.plan?.id ?? result.id))
    await load()
  } catch (e) { error.value = (e as Error).message } finally { formSubmitting.value = false }
}
async function savePlan() {
  if (formSubmitting.value) return
  const value = form.value
  if (!value.title.trim()) { error.value = '请填写计划标题'; return }
  if (value.endDate && value.startDate && value.endDate < value.startDate) { error.value = '结束日期不能早于开始日期'; return }
  formSubmitting.value = true; error.value = ''
  try {
    const body: any = { title: value.title.trim(), subject: value.subject.trim(), goal: value.goal.trim(), startDate: plannedStart() }
    body.endDate = value.endDate || null
    body.groupId = value.groupId || null
    await send('/plans/' + planId.value, 'PATCH', body)
    formOpen.value = false; editing.value = false
    showNotice('计划已更新')
    await load()
  } catch (e) { error.value = (e as Error).message } finally { formSubmitting.value = false }
}
function archivePlan() { run(async () => { await send('/plans/' + planId.value + '/archive', 'PATCH', { archived: true }); showNotice('计划已归档') }) }
function restorePlan() { run(async () => { await send('/plans/' + planId.value + '/archive', 'PATCH', { archived: false }); showNotice('计划已恢复') }) }
function removePlan() { pending.value = 'delete-plan' }
async function toggleTask(task: any) {
  if (Number(task.child_count) > 0) { error.value = '该任务包含子任务，请勾选子任务来推进进度'; return }
  error.value = ''
  try {
    const result = await send<any>('/plans/' + planId.value + '/tasks/' + task.id, 'PATCH', { status: task.status === 'done' ? 'pending' : 'done' })
    const awarded = Number(result?.pointsAwarded) || 0
    if (task.status === 'done') showNotice('已取消完成')
    else if (awarded > 0) showNotice(`任务已完成，积分 +${awarded}`)
    else showNotice('任务已完成')
    await load()
  } catch (e) { error.value = (e as Error).message }
}
async function addTask() {
  if (taskSubmitting.value) return
  if (!taskForm.value.title.trim()) { error.value = '请填写任务标题'; return }
  taskSubmitting.value = true; error.value = ''
  try {
    const body: any = { title: taskForm.value.title.trim() }
    if (taskForm.value.dueDate) body.dueDate = taskForm.value.dueDate
    if (taskForm.value.parentId) body.parentId = taskForm.value.parentId
    await send('/plans/' + planId.value + '/tasks', 'POST', body)
    taskForm.value = { title: '', dueDate: '', parentId: 0 }
    showNotice('任务已添加')
    await load()
  } catch (e) { error.value = (e as Error).message } finally { taskSubmitting.value = false }
}
async function generateTasks() {
  if (generating.value) return
  const days = Number(quickForm.value.days)
  if (!Number.isInteger(days) || days < 1 || days > 60) { error.value = '生成天数需要在 1 到 60 之间'; return }
  if (!quickForm.value.title.trim()) { error.value = '请填写任务标题模板'; return }
  generating.value = true; error.value = ''
  try {
    const start = quickForm.value.startDate || String(plan.value?.start_date || '').slice(0, 10) || today()
    const end = String(plan.value?.end_date || '').slice(0, 10) || start
    for (let i = 0; i < days; i++) {
      let due = offsetDate(start, i)
      if (end && due > end) due = end
      const label = quickForm.value.title.includes('{n}') ? quickForm.value.title.replace('{n}', String(i + 1)) : `${quickForm.value.title.trim()} 第 ${i + 1} 天`
      await send('/plans/' + planId.value + '/tasks', 'POST', { title: label, dueDate: due })
    }
    showNotice(`已生成 ${days} 个任务`)
    await load()
  } catch (e) { error.value = (e as Error).message } finally { generating.value = false }
}
function removeTask(task: any) { pendingTask.value = task; pending.value = 'delete-task' }
function saveTemplateFromPlan() { templateFromPlanForm.value = { title: plan.value?.title ? plan.value.title + ' 模板' : '', cycleDays: 7 }; templateFromPlanOpen.value = true }
async function submitTemplateFromPlan() {
  if (templateFromPlanSubmitting.value) return
  if (!templateFromPlanForm.value.title.trim()) { error.value = '请填写模板名称'; return }
  templateFromPlanSubmitting.value = true; error.value = ''
  try {
    await send('/plans/' + planId.value + '/template', 'POST', { title: templateFromPlanForm.value.title.trim(), visibility: 'shared' })
    templateFromPlanOpen.value = false
    ElMessage({ type: 'success', message: '已存为模板，可在模板市场查看' })
  } catch (e) { error.value = (e as Error).message } finally { templateFromPlanSubmitting.value = false }
}
async function createTemplate() {
  if (templateSubmitting.value) return
  if (!templateForm.value.title.trim()) { error.value = '请填写模板名称'; return }
  if (!templateForm.value.tasks.trim()) { error.value = '模板至少需要一条任务'; return }
  templateSubmitting.value = true; error.value = ''
  try {
    await send('/plans/templates', 'POST', { ...templateForm.value, title: templateForm.value.title.trim(), subject: templateForm.value.subject.trim(), goal: templateForm.value.goal.trim(), visibility: 'shared' })
    templateForm.value = { title: '', subject: templateSubject.value || '', goal: '', cycleDays: 7, tasks: '' }
    ElMessage({ type: 'success', message: '模板已发布' })
    await load()
  } catch (e) { error.value = (e as Error).message } finally { templateSubmitting.value = false }
}
function openApply(t: any) { applyForm.value = { templateId: t.id, title: t.title || '', startDate: today(), groupId: 0 } }
async function applyTemplate() {
  if (applying.value) return
  if (!applyForm.value.title.trim()) { error.value = '请填写计划标题'; return }
  applying.value = true; error.value = ''
  try {
    const body: any = { title: applyForm.value.title.trim(), startDate: applyForm.value.startDate || today() }
    if (applyForm.value.groupId) body.groupId = applyForm.value.groupId
    const result = await send<any>('/plans/templates/' + applyForm.value.templateId + '/apply', 'POST', body)
    ElMessage({ type: 'success', message: '已按模板创建计划' })
    applyForm.value = { templateId: 0, title: '', startDate: '', groupId: 0 }
    await router.push('/plans/' + (result.plan?.id ?? result.id))
  } catch (e) { error.value = (e as Error).message } finally { applying.value = false }
}
function removeTemplate(t: any) { pendingTemplate.value = t; pending.value = 'delete-template' }
async function runPendingAction() {
  const action = pending.value
  if (!action || pendingAction.value) return
  pendingAction.value = true; error.value = ''
  try {
    if (action === 'delete-plan') {
      await send('/plans/' + planId.value, 'DELETE')
      pending.value = ''
      ElMessage({ type: 'success', message: '计划已删除' })
      await router.push('/plans')
      await load()
      return
    }
    if (action === 'delete-task') {
      await send('/plans/' + planId.value + '/tasks/' + pendingTask.value.id, 'DELETE')
      ElMessage({ type: 'success', message: '任务已删除' })
      await load()
    } else {
      await send('/plans/templates/' + pendingTemplate.value.id, 'DELETE')
      ElMessage({ type: 'success', message: '模板已删除' })
      await load()
    }
  } catch (e) { error.value = (e as Error).message } finally {
    pendingAction.value = false; pending.value = ''; pendingTask.value = null; pendingTemplate.value = null
  }
}

watch(() => route.fullPath, load)
watch(current, () => { if (!isTemplates.value && !isDetail.value) load() })
onMounted(load)
</script>

<template>
  <section class="plans-page">
    <div v-if="error" class="group-alert error">{{ error }}</div>
    <div v-if="notice" class="group-alert success">{{ notice }}</div>

    <template v-if="isDetail">
      <template v-if="plan">
        <div class="group-heading">
          <div>
            <p class="eyebrow">{{ plan.subject || '学习计划' }} · {{ period(plan) }}</p>
            <h1>{{ plan.title }}</h1>
            <p>{{ plan.goal || '把目标拆成每天能完成的一小步，进度就会自己长出来。' }}</p>
            <div class="tag-list"><span class="status-pill" :class="plan.status">{{ statusText(plan.status) }}</span><span v-if="plan.group_id" class="muted">关联小组：{{ detail.groupName || '已关联' }}</span></div>
          </div>
          <div class="cover-actions">
            <button class="button ghost" @click="formOpen ? cancelForm() : startEdit()">{{ formOpen ? '收起编辑' : '编辑' }}</button>
            <button v-if="plan.status !== 'archived'" class="button ghost" @click="archivePlan"><Archive :size="15" />归档</button>
            <button v-else class="button ghost" @click="restorePlan"><Archive :size="15" />取消归档</button>
            <button class="button ghost" @click="templateFromPlanOpen = !templateFromPlanOpen"><BookmarkPlus :size="15" />存为模板</button>
            <button class="button danger" @click="removePlan">删除计划</button>
            <RouterLink class="button" to="/plans">返回列表</RouterLink>
          </div>
        </div>
        <form v-if="formOpen" class="group-card group-form" @submit.prevent="editing ? savePlan() : createPlan()">
          <label>计划标题<input v-model="form.title" required maxlength="60" placeholder="例如：30 天英语单词冲刺" /></label>
          <div class="form-two"><label>学科<input v-model="form.subject" maxlength="40" placeholder="英语 / 数学 / 编程" /></label><label>开始日期<input v-model="form.startDate" type="date" /></label><label>结束日期<input v-model="form.endDate" type="date" /></label><label>关联学习小组<select v-if="groups.length" v-model.number="form.groupId"><option :value="0">不关联</option><option v-for="g in groups" :key="g.id" :value="g.id">{{ g.name }}</option></select><input v-else disabled value="暂无可关联的小组" /></label></div>
          <label>计划目标<textarea v-model="form.goal" rows="3" maxlength="200" placeholder="写下这份计划想达成的结果"></textarea></label>
          <button class="button primary" type="submit" :disabled="formSubmitting">{{ formSubmitting ? '保存中…' : '保存计划' }}</button>
        </form>
        <form v-if="templateFromPlanOpen" class="group-card group-form" @submit.prevent="submitTemplateFromPlan">
          <label>模板名称<input v-model="templateFromPlanForm.title" required maxlength="60" placeholder="例如：30 天单词冲刺模板" /></label>
          <label>周期天数（{{ templateFromPlanForm.cycleDays }} 天）<input v-model.number="templateFromPlanForm.cycleDays" type="range" min="1" max="60" /></label>
          <div class="form-actions"><button class="button primary" type="submit" :disabled="templateFromPlanSubmitting">{{ templateFromPlanSubmitting ? '保存中…' : '确认存为模板' }}</button><button class="button ghost" type="button" @click="templateFromPlanOpen = false">取消</button></div>
        </form>

        <div class="group-card plan-progress">
          <div class="progress-head"><strong>{{ progress.percent }}%</strong><span>{{ progress.done }}/{{ progress.total }} 个顶层任务已完成</span></div>
          <div class="progress-bar"><i :style="{ width: progress.percent + '%' }"></i></div>
          <div class="stat-strip">
            <div><b>{{ progress.doneToday }}</b><span>今日完成</span></div>
            <div :class="{ alert: progress.overdue > 0 }"><b>{{ progress.overdue }}</b><span>逾期任务</span></div>
            <div><b>{{ detail.focusMinutes ?? 0 }}</b><span>专注分钟</span></div>
            <div><b>{{ detail.groupName || '—' }}</b><span>关联小组</span></div>
          </div>
        </div>

        <div class="group-card tasks-panel">
          <div class="panel-heading"><div><h2>任务拆解</h2><p>顶层任务计入进度；子任务可以再拆细，父任务由子任务自动汇总。</p></div></div>
          <div class="task-list">
            <div v-for="task in rootTasks" :key="task.id" class="task-group">
              <div class="task-row">
                <input type="checkbox" class="task-check" :checked="parentDone(task)" :indeterminate="childrenDone(task) > 0 && !parentDone(task)" :disabled="Number(task.child_count) > 0" :title="Number(task.child_count) > 0 ? '该任务包含子任务，请勾选子任务来推进进度' : '标记完成'" :aria-label="'完成任务：' + task.title" @change="toggleTask(task)" />
                <div class="task-copy"><b>{{ task.title }}</b><small><span v-if="task.due_date" :class="['due', isOverdue(task) ? 'overdue' : '']">截止 {{ fmt(task.due_date) }}<template v-if="isOverdue(task)"> · 已逾期</template></span><span v-else>未设置截止日期</span><span v-if="task.completed_at"> · 完成于 {{ fmt(lastChildDone(task)) }}</span></small></div>
                <span v-if="Number(task.child_count) > 0" class="child-badge">子任务 {{ childrenDone(task) }}/{{ Number(task.child_count) }}</span>
                <button class="text-button danger" type="button" @click="removeTask(task)"><Trash2 :size="14" />删除</button>
              </div>
              <div v-for="child in (childrenOf[task.id] || [])" :key="child.id" class="task-row child">
                <input type="checkbox" class="task-check" :checked="child.status === 'done'" :aria-label="'完成子任务：' + child.title" @change="toggleTask(child)" />
                <div class="task-copy"><b>{{ child.title }}</b><small><span v-if="child.due_date" :class="['due', isOverdue(child) ? 'overdue' : '']">截止 {{ fmt(child.due_date) }}<template v-if="isOverdue(child)"> · 已逾期</template></span><span v-else>未设置截止日期</span><span v-if="child.completed_at"> · 完成于 {{ fmt(child.completed_at) }}</span></small></div>
                <button class="text-button danger" type="button" @click="removeTask(child)"><Trash2 :size="14" />删除</button>
              </div>
            </div>
            <div v-if="!rootTasks.length" class="empty-state"><CalendarDays :size="18" /><span>还没有任务，先添加第一个任务或用「按天生成任务」铺开节奏。</span></div>
          </div>
        </div>

        <form class="group-card group-form" @submit.prevent="addTask">
          <div class="panel-heading"><div><h2>添加任务</h2><p>给任务一个截止日期，逾期会自动提醒。</p></div></div>
          <div class="form-three"><label>任务标题<input v-model="taskForm.title" required maxlength="100" placeholder="例如：背 50 个单词" /></label><label>截止日期<input v-model="taskForm.dueDate" type="date" /></label><label>父任务<select v-model.number="taskForm.parentId"><option :value="0">顶层任务</option><option v-for="t in rootTasks" :key="t.id" :value="t.id">{{ t.title }}</option></select></label></div>
          <button class="button primary" type="submit" :disabled="taskSubmitting"><Plus :size="15" />{{ taskSubmitting ? '添加中…' : '添加任务' }}</button>
        </form>

        <form class="group-card group-form" @submit.prevent="generateTasks">
          <div class="panel-heading"><div><h2>按天生成任务</h2><p>按开始日期逐天铺开任务，超出结束日期的部分会夹到最后一天。</p></div></div>
          <div class="form-three"><label>任务标题模板<input v-model="quickForm.title" maxlength="100" placeholder="例如：背 50 个单词（可用 {n} 表示第几天）" /></label><label>生成天数<input v-model.number="quickForm.days" type="number" min="1" max="60" /></label><label>起始日期<input v-model="quickForm.startDate" type="date" :placeholder="String(plan.start_date || '').slice(0, 10)" /></label></div>
          <button class="button ghost" type="submit" :disabled="generating">{{ generating ? '生成中…' : '生成任务' }}</button>
        </form>
      </template>
      <div v-else-if="!loading" class="empty-state group-card">这份学习计划不存在，或你没有查看权限。</div>
    </template>

    <template v-else-if="isTemplates">
      <div class="group-heading">
        <div>
          <p class="eyebrow">PLAN TEMPLATES</p>
          <h1>模板市场</h1>
          <p>把验证过的节奏分享出来，也可以直接套用别人的计划结构，从一个好的开始日起跑。</p>
        </div>
        <div class="cover-actions"><button class="button ghost" @click="templateOpen = !templateOpen">{{ templateOpen ? '收起表单' : '新建模板' }}</button><RouterLink class="button" to="/plans">返回计划</RouterLink></div>
      </div>
      <form v-if="templateOpen" class="group-card group-form" @submit.prevent="createTemplate">
        <label>模板名称<input v-model="templateForm.title" required maxlength="60" placeholder="例如：7 天英语单词冲刺" /></label>
        <div class="form-two"><label>学科<input v-model="templateForm.subject" maxlength="40" placeholder="英语 / 数学 / 编程" /></label><label>周期天数<input v-model.number="templateForm.cycleDays" type="number" min="1" max="365" required /></label></div>
        <label>计划目标<textarea v-model="templateForm.goal" rows="2" maxlength="200" placeholder="这份模板适合谁、想达成什么"></textarea></label>
        <label>任务清单（一行一个任务）<textarea v-model="templateForm.tasks" rows="6" required maxlength="2000" placeholder="背 50 个单词|第 3 天&#10;做 2 篇阅读理解&#10;复习昨天的错题"></textarea></label>
        <button class="button primary" type="submit" :disabled="templateSubmitting">{{ templateSubmitting ? '发布中…' : '发布模板' }}</button>
      </form>
      <div class="group-card group-filters"><input v-model="templateSubject" list="plan-subject-options" placeholder="学科筛选，如：英语" @change="load" /><button class="button ghost" type="button" @click="load">筛选</button></div>
      <div class="template-grid">
        <article v-for="t in templates" :key="t.id" class="group-card template-card">
          <div class="tile-top"><span class="subject-pill">{{ t.subject || '通用' }}</span><span class="visibility">{{ t.owner_id == null ? '平台内置' : ownerName(t) }}</span></div>
          <h2>{{ t.title }}</h2>
          <p>{{ t.goal || '这套节奏适合想稳定推进的同学。' }}</p>
          <div class="tile-meta"><span>周期 {{ t.cycle_days }} 天</span><span>{{ taskLines(t).length }} 项任务</span><span>{{ t.use_count || 0 }} 次使用</span></div>
          <ul class="template-tasks"><li v-for="(line, index) in taskLines(t).slice(0, 8)" :key="index">{{ line }}</li></ul>
          <p v-if="taskLines(t).length > 8" class="muted">等 {{ taskLines(t).length }} 项任务</p>
          <div class="card-actions"><button class="button primary" type="button" @click="openApply(t)">使用此模板</button><button v-if="t.owner_id === session.user?.id" class="text-button danger" type="button" @click="removeTemplate(t)"><Trash2 :size="14" />删除</button></div>
          <form v-if="applyForm.templateId === t.id" class="apply-form" @submit.prevent="applyTemplate">
            <input v-model="applyForm.title" required maxlength="60" placeholder="计划标题" />
            <input v-model="applyForm.startDate" type="date" />
            <select v-if="groups.length" v-model.number="applyForm.groupId"><option :value="0">不关联小组</option><option v-for="g in groups" :key="g.id" :value="g.id">{{ g.name }}</option></select>
            <div class="form-actions"><button class="button primary" type="submit" :disabled="applying">{{ applying ? '创建中…' : '确认创建' }}</button><button class="button ghost" type="button" @click="applyForm.templateId = 0">取消</button></div>
          </form>
        </article>
        <div v-if="!templates.length" class="empty-state group-card">还没有可用的模板，先发布一个属于你的节奏。</div>
      </div>
    </template>

    <template v-else>
      <div class="group-heading">
        <div>
          <p class="eyebrow">PLAN YOUR PROGRESS</p>
          <h1>学习计划</h1>
          <p>把大目标拆成每天能完成的小任务，进度、逾期和连续执行都会自动记账。</p>
        </div>
        <div class="cover-actions"><RouterLink v-if="session.user" class="button ghost" to="/plans/templates">模板市场</RouterLink><button v-if="session.user" class="button primary" @click="openCreate"><Plus :size="15" />新建计划</button><RouterLink v-else class="button primary" to="/login?next=/plans">登录后创建计划</RouterLink></div>
      </div>
      <div class="group-card stat-strip">
        <div><b>{{ stats.total ?? 0 }}</b><span>计划总数</span></div>
        <div><b>{{ stats.active ?? 0 }}</b><span>进行中</span></div>
        <div><b>{{ stats.completed ?? 0 }}</b><span>已完成</span></div>
        <div><b>{{ stats.rate ?? 0 }}%</b><span>任务完成率</span></div>
        <div :class="{ alert: (stats.today ?? 0) > 0 }"><b>{{ stats.today ?? 0 }}</b><span>今日待办</span></div>
        <div :class="{ alert: (stats.overdue ?? 0) > 0 }"><b>{{ stats.overdue ?? 0 }}</b><span>逾期任务</span></div>
        <div><b>{{ stats.streak ?? 0 }} 天</b><span>连续执行</span></div>
      </div>
      <datalist id="plan-subject-options"><option v-for="s in subjects" :key="s" :value="s"></option></datalist>
      <div class="group-card list-filters">
        <div class="status-tabs"><button v-for="item in [{ id: 'active', name: '进行中' }, { id: 'completed', name: '已完成' }, { id: 'archived', name: '已归档' }, { id: 'all', name: '全部' }]" :key="item.id" type="button" :class="['status-tab', current === item.id ? 'active' : '']" @click="current = item.id">{{ item.name }}</button></div>
        <div class="subject-filter"><input v-model="subjectFilter" list="plan-subject-options" placeholder="学科筛选，如：英语" @change="applySubject" /><button class="button ghost" type="button" @click="subjectFilter = ''; applySubject()">清空</button></div>
      </div>
      <form v-if="formOpen && !editing" class="group-card group-form" @submit.prevent="createPlan">
        <div class="panel-heading"><div><h2>新建学习计划</h2><p>先定周期和学科，再往里填任务。</p></div></div>
        <label>计划标题<input v-model="form.title" required maxlength="60" placeholder="例如：30 天英语单词冲刺" /></label>
        <div class="form-two"><label>学科<input v-model="form.subject" maxlength="40" placeholder="英语 / 数学 / 编程" /></label><label>开始日期<input v-model="form.startDate" type="date" /></label><label>结束日期<input v-model="form.endDate" type="date" /></label><label>关联学习小组<select v-if="groups.length" v-model.number="form.groupId"><option :value="0">不关联</option><option v-for="g in groups" :key="g.id" :value="g.id">{{ g.name }}</option></select><input v-else disabled value="暂无可关联的小组" /></label></div>
        <label>计划目标<textarea v-model="form.goal" rows="3" maxlength="200" placeholder="写下这份计划想达成的结果"></textarea></label>
        <div class="form-actions"><button class="button primary" type="submit" :disabled="formSubmitting">{{ formSubmitting ? '创建中…' : '创建计划' }}</button><button class="button ghost" type="button" @click="cancelForm">取消</button></div>
      </form>
      <div class="plan-grid">
        <article v-for="p in plans" :key="p.id" class="group-card plan-card" @click="router.push('/plans/' + p.id)">
          <div class="tile-top"><span class="subject-pill">{{ p.subject || '通用' }}</span><span class="status-pill" :class="p.status">{{ statusText(p.status) }}</span></div>
          <h2>{{ p.title }}</h2>
          <p>{{ p.goal || '还没有写下这份计划的目标。' }}</p>
          <div class="progress-bar"><i :style="{ width: (p.progress?.percent || 0) + '%' }"></i></div>
          <div class="tile-meta"><span>{{ p.progress?.percent || 0 }}% 完成</span><span>{{ p.progress?.done || 0 }}/{{ p.progress?.total || 0 }} 个任务</span><span>{{ period(p) }}</span></div>
          <div v-if="(p.progress?.doneToday || 0) > 0 || (p.progress?.overdue || 0) > 0" class="tile-flags"><span v-if="(p.progress?.doneToday || 0) > 0" class="flag ok">今日完成 {{ p.progress.doneToday }}</span><span v-if="(p.progress?.overdue || 0) > 0" class="flag overdue">逾期 {{ p.progress.overdue }}</span></div>
        </article>
        <div v-if="!plans.length && !formOpen" class="empty-state group-card"><Target :size="18" /><span>这个筛选下还没有计划，换一个状态或新建一份计划。</span></div>
      </div>
    </template>

    <ConfirmDialog :open="!!pending" :title="confirmConfig.title" :subject="confirmConfig.subject" :description="confirmConfig.description" :confirm-text="confirmConfig.confirmText" :pending-text="confirmConfig.pendingText" :pending="pendingAction" @cancel="pending = ''; pendingTask = null; pendingTemplate = null" @confirm="runPendingAction">
      <template #icon>
        <BookmarkPlus v-if="confirmConfig.icon === 'template'" :size="18" />
        <Target v-else-if="confirmConfig.icon === 'plan'" :size="18" />
        <Trash2 v-else :size="18" />
      </template>
    </ConfirmDialog>
  </section>
</template>

<style scoped>
.plans-page{max-width:1120px;margin:0 auto;padding:18px 0 60px;color:#203d36}.group-heading{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin:10px 0 24px}.group-heading h1{margin:3px 0 8px;font-family:Georgia,serif;font-size:42px;letter-spacing:-1px}.group-heading p{color:#617772;max-width:650px;line-height:1.7}.eyebrow{margin:0;color:#0b7771!important;font-size:11px!important;font-weight:800;letter-spacing:1.8px;text-transform:uppercase}.group-card{border:1px solid #dce8e1;border-radius:14px;background:#fff;box-shadow:0 10px 25px rgba(31,75,62,.045)}.group-alert{margin:8px 0 14px;padding:12px 15px;border-radius:8px}.group-alert.error{background:#fff2f0;color:#9e463c}.group-alert.success{background:#edf8f2;color:#237358}.button{display:inline-flex;align-items:center;justify-content:center;gap:6px;border:1px solid #bfd5cb;border-radius:7px;padding:10px 15px;background:#fff;color:#20584f;cursor:pointer;text-decoration:none;font-weight:700}.button.primary{background:#0b7771;border-color:#0b7771;color:#fff}.button.ghost{background:#eff7f3}.button.danger{background:#c94d43;border-color:#c94d43;color:#fff}.button.danger:hover{background:#ad3d35;border-color:#ad3d35}.button:disabled{opacity:.6;cursor:default}.text-button{display:inline-flex;align-items:center;gap:5px;border:1px solid #f0d4d0;border-radius:6px;padding:6px 10px;background:#fff;color:#b6574d;font-size:12px;font-weight:700;cursor:pointer}.text-button.danger:hover{border-color:#c9685c;background:#fff8f7;color:#a33d32}.cover-actions{display:flex;flex-wrap:wrap;align-items:center;gap:8px;justify-content:flex-end}.group-form{display:flex;flex-direction:column;gap:14px;padding:20px;margin-bottom:20px}.group-form label{display:flex;flex-direction:column;gap:6px;font-size:13px;font-weight:700;color:#33574f}.group-form input,.group-form textarea,.group-form select,.apply-form input,.apply-form select,.group-filters input{width:100%;box-sizing:border-box;border:1px solid #d4e1da;border-radius:7px;padding:10px 12px;background:#fbfdfc;color:#263e39;font:inherit}.group-form input[type=range]{padding:0}.form-two{display:grid;grid-template-columns:1fr 1fr;gap:14px}.form-three{display:grid;grid-template-columns:2fr 1fr 1fr;gap:14px}.form-actions{display:flex;gap:10px;align-items:center}.panel-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.panel-heading h2{margin:0 0 4px;font-size:18px}.panel-heading p{margin:0;color:#617772;font-size:13px}.stat-strip{display:grid;grid-template-columns:repeat(7,1fr);gap:10px;padding:16px 18px;margin-bottom:20px}.plan-progress{padding:18px;margin-bottom:20px}.plan-progress .stat-strip{padding:14px 0 0;margin:0;grid-template-columns:repeat(4,1fr)}.stat-strip div{display:flex;flex-direction:column;gap:3px}.stat-strip b{font-size:20px;font-family:Georgia,serif}.stat-strip span{color:#617772;font-size:12px}.stat-strip .alert b{color:#c05a4e}.progress-head{display:flex;align-items:baseline;gap:10px}.progress-head strong{font-size:28px;font-family:Georgia,serif;color:#0b7771}.progress-head span{color:#617772;font-size:13px}.progress-bar{height:8px;border-radius:99px;background:#e8f1ec;overflow:hidden;margin:10px 0}.progress-bar i{display:block;height:100%;border-radius:99px;background:linear-gradient(90deg,#0b7771,#4fb69b)}.list-filters{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:12px 14px;margin-bottom:20px;flex-wrap:wrap}.status-tabs{display:flex;gap:6px;flex-wrap:wrap}.status-tab{border:1px solid #dce8e1;border-radius:99px;padding:7px 14px;background:#fff;color:#4c6b63;font-size:13px;font-weight:700;cursor:pointer}.status-tab.active{background:#0b7771;border-color:#0b7771;color:#fff}.subject-filter{display:flex;align-items:center;gap:8px;min-width:260px}.subject-filter input{flex:1}.group-filters{display:flex;align-items:center;gap:8px;padding:14px;margin-bottom:20px}.group-filters input{flex:1}.plan-grid,.template-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}.plan-card{padding:18px;display:flex;flex-direction:column;gap:8px;cursor:pointer;transition:box-shadow .18s ease,transform .18s ease}.plan-card:hover{box-shadow:0 14px 30px rgba(31,75,62,.1);transform:translateY(-2px)}.plan-card h2,.template-card h2{margin:2px 0;font-size:17px}.plan-card p,.template-card p{margin:0;color:#617772;font-size:13px;line-height:1.6}.tile-top{display:flex;align-items:center;justify-content:space-between;gap:8px}.tile-meta{display:flex;flex-wrap:wrap;gap:10px;color:#617772;font-size:12px}.tile-flags{display:flex;gap:8px;flex-wrap:wrap}.flag{border-radius:99px;padding:3px 9px;font-size:12px;font-weight:700}.flag.ok{background:#edf8f2;color:#237358}.flag.overdue{background:#fdeeec;color:#b6574d}.subject-pill,.visibility,.status-pill{border-radius:99px;padding:3px 10px;font-size:11px;font-weight:800;letter-spacing:.4px}.subject-pill{background:#eaf4f0;color:#0b7771}.visibility{background:#f3f6f4;color:#5d756e}.status-pill{background:#f1f5f3;color:#5d756e}.status-pill.active{background:#e7f4ef;color:#0b7771}.status-pill.completed{background:#e9f2fb;color:#2f6fa8}.status-pill.archived{background:#f2f2f0;color:#7a857f}.tag-list{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-top:8px}.muted{color:#82968e;font-size:12px}.tasks-panel{padding:18px;margin-bottom:20px}.task-list{display:flex;flex-direction:column;margin-top:8px}.task-group{border-top:1px solid #e7efea;padding:6px 0}.task-group:first-child{border-top:0}.task-row{display:flex;align-items:flex-start;gap:12px;padding:10px 4px}.task-row.child{padding-left:34px;background:#fbfdfc;border-radius:8px}.task-check{width:17px;height:17px;margin-top:2px;accent-color:#0b7771;flex:none}.task-check:disabled{cursor:not-allowed}.task-copy{flex:1;min-width:0;display:flex;flex-direction:column;gap:3px}.task-copy b{font-size:14px}.task-copy small{display:flex;flex-wrap:wrap;gap:8px;color:#82968e;font-size:12px}.due.overdue{color:#c05a4e;font-weight:700}.child-badge{border-radius:99px;padding:3px 10px;background:#f1f5f3;color:#5d756e;font-size:11px;font-weight:800;white-space:nowrap}.template-card{padding:18px;display:flex;flex-direction:column;gap:10px}.template-tasks{margin:0;padding-left:18px;color:#3f5c55;font-size:13px;line-height:1.8}.template-tasks li{padding-left:2px}.card-actions{display:flex;align-items:center;gap:10px;margin-top:auto}.apply-form{display:flex;flex-direction:column;gap:10px;border-top:1px solid #e7efea;padding-top:12px;margin-top:8px}.empty-state{display:flex;align-items:center;justify-content:center;gap:10px;padding:26px;color:#82968e;font-size:13px;text-align:center}
@media(max-width:900px){.stat-strip{grid-template-columns:repeat(4,1fr)}.plan-grid,.template-grid{grid-template-columns:repeat(2,1fr)}.form-three{grid-template-columns:1fr 1fr}.cover-actions{justify-content:flex-start}.group-heading{align-items:flex-start}}
@media(max-width:620px){.plans-page{padding:10px}.group-heading{flex-direction:column;align-items:flex-start}.group-heading h1{font-size:32px}.plan-grid,.template-grid,.form-two,.form-three{grid-template-columns:1fr}.stat-strip{grid-template-columns:repeat(2,1fr)}.list-filters{flex-direction:column;align-items:stretch}.subject-filter{min-width:0}.task-row.child{padding-left:18px}}
</style>
