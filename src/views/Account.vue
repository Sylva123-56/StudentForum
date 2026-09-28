<script setup lang="ts">
import {computed, onMounted, ref, watch} from 'vue'
import {useRoute} from 'vue-router'
import {api, date, level, roleBadge, send} from '../api'
import {useSession} from '../store'
import {Heart, MessageCircle, Trash2} from 'lucide-vue-next'

type Notification = {
  id: number;
  kind: string;
  message: string;
  post_id: number | null;
  is_read: boolean;
  created_at: string
}
const route = useRoute(), session = useSession(),
    tab = ref(route.path.includes('notifications') ? 'notifications' : 'profile'), favorites = ref<any[]>([]),
    logs = ref<any[]>([]), notifications = ref<Notification[]>([]), publicUser = ref<any>(), error = ref(''),
    notificationError = ref(''), saved = ref(''), social = ref<any>({}), following = ref<any[]>([]),
    followers = ref<any[]>([]), reputationLogs = ref<any[]>([]), authoredPosts = ref<any[]>([])
const mine = computed(() => !route.params.id)
const notificationPage = ref(1), notificationHasNext = ref(false)
const profile = ref({school: '', grade: '', major: '', subjectPreference: '', publicSchool: false, publicGrade: false})
onMounted(async () => {
  if (!mine.value) {
    publicUser.value = await api('/users/' + route.params.id);
    social.value = await api('/users/' + route.params.id + '/social');
    return
  }
  await session.refresh();
  const user = session.user;
  if (!user) return;
  profile.value = {
    school: user.school || '',
    grade: user.grade || '',
    major: user.major || '',
    subjectPreference: user.subject_preference || '',
    publicSchool: user.public_school,
    publicGrade: user.public_grade
  };
  await reloadNotifications();
  [favorites.value, logs.value, following.value, followers.value, social.value, authoredPosts.value] = await Promise.all([api('/me/favorites').catch(() => []), api('/me/point-logs').catch(() => []), api('/me/following').catch(() => []), api('/me/followers').catch(() => []), api('/users/' + user.id + '/social').catch(() => ({})), api('/me/posts').catch(() => [])]);
  reputationLogs.value = (await api<{ logs: any[] }>('/me/reputation').catch(() => ({logs: []}))).logs
})
watch(() => route.path, path => {
  tab.value = path.includes('notifications') ? 'notifications' : 'profile';
  if (tab.value === 'notifications') { notificationPage.value = 1; reloadNotifications() }
})

async function reloadNotifications() {
  try {
    const result = await api<{ items: Notification[], unread: number, hasNext: boolean }>('/notifications?page=' + notificationPage.value);
    notifications.value = result.items;
    notificationHasNext.value = result.hasNext;
    session.unread = result.unread;
    notificationError.value = ''
  } catch (exception) {
    notificationError.value = (exception as Error).message
  }
}
function changeNotificationPage(page: number) {
  notificationPage.value = page;
  reloadNotifications()
}

async function readNotification(item: Notification) {
  if (!item.is_read) {
    try {
      await send('/notifications/' + item.id + '/read', 'PATCH');
      item.is_read = true;
      session.unread = Math.max(0, session.unread - 1)
    } catch (exception) {
      notificationError.value = (exception as Error).message
    }
  }
}

function notificationKind(kind: string) {
  return ({
    system: '系统公告',
    report: '举报处理结果',
    reply: '帖子回复',
    accepted: '回答被采纳',
    featured: '帖子加精',
    follow: '新关注',
    unfollow: '取关',
    mention: '提及',
    appeal: '申诉'
  } as Record<string, string>)[kind] || '通知'
}

async function save() {
  try {
    await send('/me/profile', 'PUT', profile.value);
    await session.refresh();
    saved.value = '资料已保存'
  } catch (exception) {
    error.value = (exception as Error).message
  }
}

async function follow() {
  try {
    await send('/follows/' + route.params.id, social.value.followed ? 'DELETE' : 'POST');
    social.value = await api('/users/' + route.params.id + '/social')
  } catch (exception) {
    error.value = (exception as Error).message
  }
}

async function chat() {
  try {
    const result = await send<{ id: number }>('/conversations', 'POST', {userId: Number(route.params.id)});
    window.location.href = '/messages/' + result.id
  } catch (exception) {
    error.value = (exception as Error).message
  }
}

async function block() {
  try {
    await send('/users/' + route.params.id + '/block', 'POST');
    saved.value = '已拉黑'
  } catch (exception) {
    error.value = (exception as Error).message
  }
}

async function readAll() {
  try {
    await send('/notifications/read-all', 'POST');
    await reloadNotifications()
  } catch (exception) {
    notificationError.value = (exception as Error).message
  }
}

async function deletePost(post: any) {
  if (!window.confirm('确定要删除这篇帖子吗？删除后将无法在个人空间查看。')) return
  try {
    await send('/posts/' + post.id, 'DELETE')
    authoredPosts.value = authoredPosts.value.filter(item => item.id !== post.id)
  } catch (exception) {
    error.value = (exception as Error).message
  }
}
</script>
<template>
  <div class="content-page">
    <div v-if="!mine && publicUser">
      <div class="profile-hero"><span class="profile-avatar">{{ publicUser.username[0] }}</span>
        <div><h1>{{ publicUser.username }}<span v-if="roleBadge(publicUser.role, publicUser.moderator_board_name)"
              class="role-badge" :class="publicUser.role">{{ roleBadge(publicUser.role, publicUser.moderator_board_name) }}</span></h1>
          <p>{{ publicUser.school }} {{ publicUser.grade }}</p><span>Lv{{
              publicUser.points >= 1000 ? 5 : publicUser.points >= 500 ? 4 : publicUser.points >= 200 ? 3 : publicUser.points >= 50 ? 2 : 1
            }} {{ level(publicUser.points) }} · {{ publicUser.points }} 积分</span></div>
      </div>
      <div class="v2-social">关注 {{ social.following }} · 粉丝 {{ social.followers }} · 获赞 {{ social.likes }} · 采纳
        {{ social.accepted }} · 精华 {{ social.featured }}
      </div>
      <div v-if="session.user && session.user.id!==publicUser.id" class="v2-inline">
        <button class="button primary" @click="follow">{{ social.followed ? '取消关注' : '关注' }}</button>
        <button class="button" @click="chat">私信</button>
        <button class="button" @click="block">拉黑</button>
      </div>
      <p v-if="error" class="error">{{ error }}</p></div>
    <template v-else-if="session.user">
      <div class="page-heading"><span>个人空间</span>
        <h1>你好，{{ session.user.username }}</h1>
        <p>记录你的学习足迹，管理你关注的讨论。</p></div>
      <div v-if="session.user.status === 'muted'" class="profile-mute-notice" role="status">
        <strong>账号已被禁言</strong>
        <p>目前无法发布内容或发送私信。如认为处理有误，可以提交申诉。</p>
        <RouterLink to="/appeals">前往申诉</RouterLink>
      </div>
      <div class="profile-summary"><span class="profile-avatar">{{ session.user.username[0] }}</span>
        <div><strong>{{ session.user.username }}<span
            v-if="roleBadge(session.user.role, session.user.moderator_board_name)" class="role-badge"
            :class="session.user.role">{{ roleBadge(session.user.role, session.user.moderator_board_name) }}</span></strong><span>Lv{{
            session.user.points >= 1000 ? 5 : session.user.points >= 500 ? 4 : session.user.points >= 200 ? 3 : session.user.points >= 50 ? 2 : 1
          }} {{ level(session.user.points) }}</span></div>
        <div class="points-num">{{ session.user.points }}<small>当前积分</small></div>
      </div>
      <div class="v2-social">关注 {{ social.following }} · 粉丝 {{ social.followers }} · 获赞 {{ social.likes }} · 采纳
        {{ social.accepted }} · 精华 {{ social.featured }} · 信誉分 {{ session.user.reputation }}
      </div>
      <div class="v2-inline">
        <RouterLink to="/me/drafts">草稿箱</RouterLink>
        <RouterLink to="/messages">私信</RouterLink>
        <RouterLink to="/appeals">申诉</RouterLink>
        <RouterLink to="/me/notifications/settings">通知设置</RouterLink>
      </div>
      <div class="account-tabs-row">
        <div class="tabs">
          <button
              v-for="item in [{id:'profile',name:'个人资料'},{id:'favorites',name:'我的收藏'},{id:'notifications',name:'通知'},{id:'points',name:'积分明细'},{id:'following',name:'我的关注'},{id:'followers',name:'我的粉丝'},{id:'posts',name:'动态'},{id:'reputation',name:'信誉记录'}]"
              :key="item.id" :class="{active:tab===item.id}" @click="tab=item.id">{{ item.name }}<span
              v-if="item.id==='notifications' && session.unread"> {{ session.unread }}</span></button>
        </div>
        <button v-if="tab==='notifications' && notifications.length" class="mark-all-read" type="button" @click="readAll">全部标记已读</button>
      </div>
      <form v-if="tab==='profile'" class="profile-form" @submit.prevent="save">
        <div class="form-row"><label>学校<input v-model="profile.school" maxlength="100"
                                                placeholder="你的学校"/></label><label>年级<input
            v-model="profile.grade" maxlength="40" placeholder="例如 大二"/></label></div>
        <div class="form-row"><label>专业 / 班级<input v-model="profile.major"
                                                       maxlength="100"/></label><label>学科偏好<input
            v-model="profile.subjectPreference" maxlength="100"/></label></div>
        <label class="check"><input v-model="profile.publicSchool" type="checkbox"/> 公开学校</label><label
          class="check"><input v-model="profile.publicGrade" type="checkbox"/> 公开年级</label>
        <button class="button primary">保存资料</button>
        <span class="success">{{ saved }}</span></form>
      <div v-if="tab==='favorites'" class="account-list">
        <div v-if="!favorites.length" class="empty">还没有收藏，遇到好内容记得保存。</div>
        <RouterLink v-for="item in favorites" :key="item.id" :to="'/posts/'+item.id"><strong>{{
            item.title
          }}</strong><span>{{ item.board_name }} · {{ date(item.created_at) }}</span></RouterLink>
      </div>
      <div v-if="tab==='notifications'" class="account-list">
        <p v-if="notificationError" class="error">通知加载失败：{{ notificationError }}
          <button type="button" @click="reloadNotifications">重试</button>
        </p>
        <div v-else-if="!notifications.length" class="empty">{{ notificationPage > 1 ? '本页没有通知，请返回上一页。' : '暂时没有新消息。' }}</div>
        <article v-for="item in notifications" :key="item.id" class="notification-item" :class="{unread:!item.is_read}">
          <div><span class="notification-kind">{{ notificationKind(item.kind) }}</span><span class="notification-date">{{
              date(item.created_at)
            }}</span></div>
          <p>{{ item.message }}</p>
          <div class="notification-actions">
            <RouterLink v-if="item.post_id" :to="'/posts/'+item.post_id" @click="readNotification(item)">查看帖子
            </RouterLink>
            <button v-if="!item.is_read" type="button" @click="readNotification(item)">标记已读</button>
          </div>
        </article>
        <nav v-if="!notificationError && (notificationPage > 1 || notificationHasNext)" class="pager" aria-label="通知分页">
          <button :disabled="notificationPage === 1" @click="changeNotificationPage(notificationPage - 1)">上一页</button>
          <span>第 {{ notificationPage }} 页</span>
          <button :disabled="!notificationHasNext" @click="changeNotificationPage(notificationPage + 1)">下一页</button>
        </nav>
      </div>
      <div v-if="tab==='posts'" class="account-list authored-post-list">
        <div v-if="!authoredPosts.length" class="empty">还没有发布过帖子。</div>
        <article v-for="item in authoredPosts" :key="item.id" class="authored-post">
          <div class="authored-post-main">
            <RouterLink :to="'/posts/'+item.id" class="authored-post-title">{{ item.title }}</RouterLink>
            <div class="authored-post-meta"><span>{{ item.board_name }} · {{ date(item.created_at) }}</span><span><MessageCircle :size="14" /> {{ item.reply_count || 0 }} 条评论</span><span><Heart :size="14" /> {{ item.favorite_count || 0 }} 个赞</span></div>
          </div>
          <button class="post-delete" type="button" title="删除帖子" aria-label="删除帖子" @click="deletePost(item)"><Trash2 :size="16" /> 删除</button>
        </article>
      </div>
      <div v-if="tab==='reputation'" class="account-list">
        <article v-for="item in reputationLogs" :key="item.id" class="v2-record">{{
            item.amount > 0 ? '+' : ''
          }}{{ item.amount }} · 当前 {{ item.balance_after }} · {{ item.reason }} · {{ date(item.created_at) }}
        </article>
      </div>
      <div v-if="tab==='following' || tab==='followers'" class="account-list">
        <RouterLink v-for="item in tab==='following'?following:followers" :key="item.id" :to="'/users/'+item.id">
          {{ item.username }}
        </RouterLink>
      </div>
      <div v-if="tab==='points'" class="account-list">
        <div v-if="!logs.length" class="empty">还没有积分记录。</div>
        <div v-for="item in logs" :key="item.id" class="log-row"><span>{{
            {
              register: '注册',
              login: '每日登录',
              post: '发帖',
              reply: '回帖',
              accepted: '回答被采纳',
              featured: '帖子加精',
              report_valid: '有效举报',
              adjust: '管理员调整'
            }[item.action as 'post'] || item.action
          }}<small>{{ date(item.created_at) }}</small></span><strong>{{ item.points > 0 ? '+' : '' }}{{
            item.points
          }}</strong></div>
      </div>
      <p v-if="error" class="error">{{ error }}</p></template>
  </div>
</template>
