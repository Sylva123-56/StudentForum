import { createRouter, createWebHistory } from 'vue-router'
import Feed from './views/Feed.vue'
import PostDetail from './views/PostDetail.vue'
import Compose from './views/Compose.vue'
import Account from './views/Account.vue'
import Auth from './views/Auth.vue'
import Admin from './views/Admin.vue'
import { useSession } from './store'
const router = createRouter({ history: createWebHistory(), routes: [
  { path: '/', component: Feed }, { path: '/boards', component: Feed }, { path: '/boards/:id', component: Feed }, { path: '/search', component: Feed }, { path: '/featured', component: Feed }, { path: '/tags/:id', component: Feed },
  { path: '/posts/new', component: Compose, meta: { login: true } }, { path: '/posts/:id', component: PostDetail },
  { path: '/login', component: Auth }, { path: '/register', component: Auth },
  { path: '/me', component: Account, meta: { login: true } }, { path: '/me/notifications', component: Account, meta: { login: true } },
  { path: '/users/:id', component: Account },
  { path: '/admin/login', component: Auth }, { path: '/admin/:section?', component: Admin, meta: { login: true } }
] })
router.beforeEach(async to => { const session = useSession(); if (!session.ready) await session.refresh(); if (to.meta.login && !session.user) return { path: '/login', query: { next: to.fullPath } } })
export default router
