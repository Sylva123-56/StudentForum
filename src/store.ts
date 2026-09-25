import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, type User } from './api'
export const useSession = defineStore('session', () => {
  const user = ref<User | null>(null)
  const ready = ref(false)
  const unread = ref(0)
  async function refresh() { try { user.value = await api<User>('/me'); unread.value = (await api<{unread:number}>('/notifications')).unread } catch { user.value = null } finally { ready.value = true } }
  return { user, ready, unread, refresh }
})
