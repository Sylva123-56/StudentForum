<script setup lang="ts">
import { computed, nextTick, onMounted, ref, useId } from 'vue'
import { Megaphone } from 'lucide-vue-next'
import { api, date, send } from '../api'
import { useSession } from '../store'

type Announcement = { id: number; message: string; created_at: string }

const session = useSession()
const titleId = useId(), bodyId = useId()
const items = ref<Announcement[]>([]), index = ref(0), busy = ref(false), visible = ref(false)
const primary = ref<HTMLButtonElement>()
const current = computed(() => items.value[index.value])
const last = computed(() => index.value + 1 >= items.value.length)

// 登录成功（或带着会话刷新页面）后拉一次未读系统公告，按时间倒序，先显示最新的一条。
onMounted(async () => {
    const list = await api<Announcement[]>('/announcements/unread').catch(() => [] as Announcement[])
    items.value = (Array.isArray(list) ? list : []).filter(item => item && item.message)
    if (!items.value.length) return
    visible.value = true
    await nextTick()
    primary.value?.focus()
})

// 读完当前这条：标记已读，还有未读就翻下一条，否则关掉。
async function next() {
    const item = current.value
    if (!item || busy.value) return
    busy.value = true
    try {
        await send('/notifications/' + item.id + '/read', 'PATCH')
        session.unread = Math.max(0, session.unread - 1)
    } catch {
        // 标记失败不影响阅读，下次进入还会再提醒
    }
    busy.value = false
    if (last.value) visible.value = false
    else index.value += 1
}

async function readAll() {
    if (busy.value) return
    busy.value = true
    try {
        await send('/announcements/read-all', 'POST')
        session.unread = Math.max(0, session.unread - items.value.length)
    } catch {
        // 忽略：关掉弹框即可，下次登录还会提醒
    }
    busy.value = false
    visible.value = false
}
</script>
<template>
    <Teleport to="body">
        <div v-if="visible && current" class="announce-backdrop" @click.self="readAll" @keydown.esc="next">
            <div class="announce" role="dialog" aria-modal="true" :aria-labelledby="titleId" :aria-describedby="bodyId">
                <div class="announce-head">
                    <span class="announce-icon" aria-hidden="true"><Megaphone :size="18" /></span>
                    <h2 :id="titleId">系统公告</h2>
                    <span v-if="items.length > 1" class="announce-count">{{ index + 1 }} / {{ items.length }}</span>
                </div>
                <p class="announce-date">{{ date(current.created_at) }}</p>
                <p :id="bodyId" class="announce-body">{{ current.message }}</p>
                <div class="announce-actions">
                    <button v-if="items.length > 1" type="button" class="button" :disabled="busy" @click="readAll">
                        全部知道了
                    </button>
                    <button ref="primary" type="button" class="button announce-primary" :disabled="busy" @click="next">
                        {{ last ? '知道了' : '下一条' }}
                    </button>
                </div>
            </div>
        </div>
    </Teleport>
</template>
<style scoped>
.announce-backdrop {
    position: fixed;
    inset: 0;
    z-index: 2200;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 22px;
    background: rgba(16, 43, 40, .38);
    backdrop-filter: blur(3px);
    animation: announce-fade .16s ease-out
}

.announce {
    width: 100%;
    max-width: 460px;
    background: #fff;
    border-radius: 14px;
    padding: 26px 26px 20px;
    box-shadow: 0 28px 60px -18px rgba(12, 43, 40, .45), 0 3px 10px rgba(12, 43, 40, .08);
    animation: announce-rise .22s cubic-bezier(.22, 1, .36, 1)
}

@keyframes announce-fade {
    from {
        opacity: 0
    }
}

@keyframes announce-rise {
    from {
        opacity: 0;
        transform: translateY(10px) scale(.98)
    }
}

.announce-head {
    display: flex;
    align-items: center;
    gap: 11px
}

.announce-icon {
    display: grid;
    place-items: center;
    flex: none;
    width: 34px;
    height: 34px;
    border-radius: 10px;
    background: #e7f2ee;
    color: #0b7771
}

.announce-head h2 {
    margin: 0;
    font-size: 17px;
    font-weight: 700;
    color: #1d3b37
}

.announce-count {
    margin-left: auto;
    padding: 3px 9px;
    border-radius: 999px;
    background: #eef5f2;
    color: #4b6b63;
    font-size: 11px;
    font-weight: 700
}

.announce-date {
    margin: 12px 0 0;
    color: #879692;
    font-size: 12px
}

.announce-body {
    margin: 10px 0 0;
    padding: 14px 16px;
    border-left: 3px solid #9fc9bd;
    border-radius: 0 8px 8px 0;
    background: #f5faf8;
    color: #253f39;
    font-size: 13.5px;
    line-height: 1.8;
    white-space: pre-wrap;
    word-break: break-word
}

.announce-actions {
    display: flex;
    justify-content: flex-end;
    gap: 10px;
    margin-top: 22px
}

.announce-actions .button {
    padding: 9px 18px;
    font-size: 13px
}

.announce-primary {
    background: #0b7771;
    border-color: #0b7771;
    color: #fff
}

.announce-primary:hover:not(:disabled) {
    background: #08625d;
    border-color: #08625d
}

@media (max-width: 520px) {
    .announce {
        padding: 22px 20px 18px
    }

    .announce-actions {
        gap: 8px
    }

    .announce-actions .button {
        flex: 1;
        padding: 10px 12px
    }
}
</style>
