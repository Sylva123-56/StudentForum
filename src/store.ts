import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, type User } from './api'
export const useSession = defineStore('session', () => {
  const user = ref<User | null>(null)
  const ready = ref(false)
  const unread = ref(0)
  async function refresh() {
    try {
      user.value = await api<User>('/me')
    } catch {
      user.value = null
      unread.value = 0
    } finally {
      ready.value = true
    }
    if (user.value) {
      try { unread.value = (await api<{unread:number}>('/notifications')).unread } catch { unread.value = 0 }
    }
  }
  return { user, ready, unread, refresh }
})
