<script setup lang="ts">
import { nextTick, ref, useId, watch } from 'vue'
import { Trash2 } from 'lucide-vue-next'
const props = withDefaults(defineProps<{
    open: boolean
    title: string
    description: string
    subject?: string
    confirmText?: string
    pendingText?: string
    pending?: boolean
}>(), { subject: '', confirmText: '确认删除', pendingText: '删除中…', pending: false })
const emit = defineEmits<{ confirm: []; cancel: [] }>()
const titleId = useId(), textId = useId()
const cancelButton = ref<HTMLButtonElement>()
watch(() => props.open, async open => { if (open) { await nextTick(); cancelButton.value?.focus() } })
</script>
<template>
    <Teleport to="body">
        <div v-if="open" class="delete-confirm-backdrop" @click.self="emit('cancel')" @keydown.esc="emit('cancel')">
            <div class="delete-confirm" role="alertdialog" aria-modal="true" :aria-labelledby="titleId"
                :aria-describedby="textId">
                <div class="delete-confirm-head">
                    <span class="delete-confirm-icon" aria-hidden="true">
                        <slot name="icon"><Trash2 :size="18" /></slot>
                    </span>
                    <h2 :id="titleId">{{ title }}</h2>
                </div>
                <p v-if="subject" class="delete-confirm-quote">{{ subject }}</p>
                <p :id="textId" class="delete-confirm-text">{{ description }}</p>
                <div class="delete-confirm-actions">
                    <button ref="cancelButton" type="button" class="button" @click="emit('cancel')">取消</button>
                    <button type="button" class="button delete-confirm-danger" :disabled="pending"
                        @click="emit('confirm')">{{ pending ? pendingText : confirmText }}</button>
                </div>
            </div>
        </div>
    </Teleport>
</template>
<style scoped>
.delete-confirm-backdrop {
    position: fixed;
    inset: 0;
    z-index: 2000;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 22px;
    background: rgba(16, 43, 40, .38);
    backdrop-filter: blur(3px);
    animation: delete-confirm-fade .16s ease-out
}

.delete-confirm {
    width: 100%;
    max-width: 420px;
    background: #fff;
    border-radius: 14px;
    padding: 26px 26px 20px;
    box-shadow: 0 28px 60px -18px rgba(12, 43, 40, .45), 0 3px 10px rgba(12, 43, 40, .08);
    animation: delete-confirm-rise .22s cubic-bezier(.22, 1, .36, 1)
}

@keyframes delete-confirm-fade {
    from { opacity: 0 }
}

@keyframes delete-confirm-rise {
    from { opacity: 0; transform: translateY(10px) scale(.98) }
}

.delete-confirm-head {
    display: flex;
    align-items: center;
    gap: 11px
}

.delete-confirm-icon {
    display: grid;
    place-items: center;
    flex: none;
    width: 34px;
    height: 34px;
    border-radius: 10px;
    background: #fbeeec;
    color: #b6574d
}

.delete-confirm-head h2 {
    margin: 0;
    font-size: 17px;
    font-weight: 700;
    color: #1d3b37
}

.delete-confirm-quote {
    margin: 16px 0 0;
    padding: 10px 14px;
    border-left: 3px solid #cf9d95;
    border-radius: 0 8px 8px 0;
    background: #fbf6f5;
    color: #6c4a44;
    font-size: 13px;
    line-height: 1.6;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden
}

.delete-confirm-text {
    margin: 14px 0 0;
    color: #6d827c;
    font-size: 13px;
    line-height: 1.75
}

.delete-confirm-actions {
    display: flex;
    justify-content: flex-end;
    gap: 10px;
    margin-top: 22px
}

.delete-confirm-actions .button {
    padding: 9px 18px;
    font-size: 13px
}

.delete-confirm-danger {
    background: #b6574d;
    border-color: #b6574d;
    color: #fff
}

.delete-confirm-danger:hover:not(:disabled) {
    background: #a33d32;
    border-color: #a33d32
}

@media (max-width: 520px) {
    .delete-confirm {
        padding: 22px 20px 18px
    }

    .delete-confirm-actions {
        gap: 8px
    }

    .delete-confirm-actions .button {
        flex: 1;
        padding: 10px 12px
    }
}
</style>
