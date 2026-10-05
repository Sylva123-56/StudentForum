import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from './store'
// 每个视图单独成 chunk：首屏只下载当前页面用到的代码，其余页面跳转时才按需加载
const Feed = () => import('./views/Feed.vue')
const PostDetail = () => import('./views/PostDetail.vue')
const Compose = () => import('./views/Compose.vue')
const Account = () => import('./views/Account.vue')
const Auth = () => import('./views/Auth.vue')
const Admin = () => import('./views/Admin.vue')
const V2Hub = () => import('./views/V2Hub.vue')
const Groups = () => import('./views/Groups.vue')
const Checkin = () => import('./views/Checkin.vue')
const Focus = () => import('./views/Focus.vue')
const Plans = () => import('./views/Plans.vue')
const StudyRooms = () => import('./views/StudyRooms.vue')
const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/', component: Feed }, { path: '/boards', component: Feed }, { path: '/boards/:id', component: Feed }, { path: '/search', component: Feed }, { path: '/featured', component: Feed }, { path: '/tags/:id', component: Feed },
  { path: '/groups', component: Groups }, { path: '/groups/new', component: Groups, meta: { login: true } },
  { path: '/groups/:id/settings', component: Groups, meta: { login: true } }, { path: '/groups/:id/members', component: Groups }, { path: '/groups/:id/files', component: Groups }, { path: '/groups/:id/checkin', component: Groups }, { path: '/groups/:id/posts/:postId', component: Groups }, { path: '/groups/:id', component: Groups },
  { path: '/posts/new', component: Compose, meta: { login: true } }, { path: '/posts/:id', component: PostDetail },
  { path: '/checkin', component: Checkin, meta: { login: true } }, { path: '/checkin/goals', component: Checkin, meta: { login: true } },
  { path: '/focus', component: Focus, meta: { login: true } }, { path: '/focus/stats', component: Focus, meta: { login: true } },
  { path: '/plans', component: Plans, meta: { login: true } }, { path: '/plans/templates', component: Plans, meta: { login: true } }, { path: '/plans/:id', component: Plans, meta: { login: true } },
  { path: '/study-rooms', component: StudyRooms }, { path: '/study-rooms/:id', component: StudyRooms },
  { path: '/login', component: Auth }, { path: '/register', component: Auth },
  { path: '/me', component: Account, meta: { login: true } }, { path: '/me/notifications', component: Account, meta: { login: true } },
  { path: '/users/:id', component: Account },
  { path: '/feed/following', component: V2Hub, meta: { login: true } },
  { path: '/messages', component: V2Hub, meta: { login: true } }, { path: '/messages/:conversationId', component: V2Hub, meta: { login: true } },
  { path: '/me/drafts', component: V2Hub, meta: { login: true } }, { path: '/me/notifications/settings', component: V2Hub, meta: { login: true } },
  { path: '/posts/:id/revisions', component: V2Hub }, { path: '/appeals', component: V2Hub, meta: { login: true } },
  { path: '/admin/login', component: Auth }, { path: '/admin/groups', component: Groups, meta: { login: true } }, { path: '/admin/groups/logs', component: Groups, meta: { login: true } }, { path: '/admin/:section?', component: Admin, meta: { login: true } }
] })
router.beforeEach(async to => { const session = useSession(); if (!session.ready) await session.refresh(); if (to.meta.login && !session.user) return { path: '/login', query: { next: to.fullPath } } })
export default router
