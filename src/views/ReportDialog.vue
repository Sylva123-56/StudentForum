<script setup lang="ts">
import { computed, nextTick, ref, useId, watch } from 'vue'
import { Flag, ShieldAlert } from 'lucide-vue-next'
const props = withDefaults(defineProps<{
    open: boolean
    targetLabel: string
    subject?: string
    pending?: boolean
}>(), { subject: '', pending: false })
const emit = defineEmits<{ submit: [payload: { reason: string; description: string }]; cancel: [] }>()
// 与服务端 ForumService.report / V2Controller.reportMessage 校验的取值保持一致，改这里必须同步改后端。
const reasons = ['广告垃圾', '人身攻击', '抄袭侵权', '色情暴力', '政治敏感', '其他']
// 默认停在"其他"，不预先替用户指认某类违规（与旧版 window.prompt 的默认值一致）。
const reason = ref('其他'), description = ref('')
const titleId = useId(), textId = useId(), reasonId = useId(), descriptionId = useId()
const reasonSelect = ref<HTMLSelectElement>()
const remaining = computed(() => 500 - description.value.length)
watch(() => props.open, async open => { if (open) { reason.value = '其他'; description.value = ''; await nextTick(); reasonSelect.value?.focus() } })
function submit() { emit('submit', { reason: reason.value, description: description.value.trim() }) }
</script>
<template>
    <Teleport to="body">
        <div v-if="open" class="report-backdrop" @click.self="emit('cancel')" @keydown.esc="emit('cancel')">
            <div class="report-dialog" role="dialog" aria-modal="true" :aria-labelledby="titleId"
                :aria-describedby="textId">
                <div class="report-head">
                    <span class="report-icon" aria-hidden="true"><Flag :size="18" /></span>
                    <div class="report-heading">
                        <h2 :id="titleId">举报{{ targetLabel }}</h2>
                        <p :id="textId">请选择最贴近的举报原因，管理员会尽快核实处理。</p>
                    </div>
                </div>
                <p v-if="subject" class="report-quote">{{ subject }}</p>
                <label class="report-field" :for="reasonId">
                    <span class="report-label">举报原因</span>
                    <div class="report-select">
                        <select :id="reasonId" ref="reasonSelect" v-model="reason">
                            <option v-for="item in reasons" :key="item" :value="item">{{ item }}</option>
                        </select>
                    </div>
                </label>
                <label class="report-field" :for="descriptionId">
                    <span class="report-label">补充说明 <em>选填</em></span>
                    <textarea :id="descriptionId" v-model="description" rows="3" maxlength="500"
                        placeholder="可以补充具体情况，方便管理员更快判断…" />
                    <span class="report-counter">{{ remaining }}</span>
                </label>
                <p class="report-note"><ShieldAlert :size="15" />恶意举报会消耗每日举报次数，今日剩余次数用完后需等到明天。</p>
                <div class="report-actions">
                    <button type="button" class="button" @click="emit('cancel')">取消</button>
                    <button type="button" class="button report-submit" :disabled="pending" @click="submit">{{
                        pending ? '提交中…' : '提交举报' }}</button>
                </div>
            </div>
        </div>
    </Teleport>
</template>
<style scoped>
.report-backdrop {
    position: fixed;
    inset: 0;
    z-index: 2000;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 22px;
    background: rgba(16, 43, 40, .38);
    backdrop-filter: blur(3px);
    animation: report-fade .16s ease-out
}

.report-dialog {
    width: 100%;
    max-width: 440px;
    max-height: calc(100vh - 44px);
    overflow-y: auto;
    background: #fff;
    border-radius: 14px;
    padding: 26px 26px 20px;
    box-shadow: 0 28px 60px -18px rgba(12, 43, 40, .45), 0 3px 10px rgba(12, 43, 40, .08);
    animation: report-rise .22s cubic-bezier(.22, 1, .36, 1)
}

@keyframes report-fade {
    from { opacity: 0 }
}

@keyframes report-rise {
    from { opacity: 0; transform: translateY(10px) scale(.98) }
}

.report-head {
    display: flex;
    align-items: flex-start;
    gap: 11px
}

.report-icon {
    display: grid;
    place-items: center;
    flex: none;
    width: 34px;
    height: 34px;
    border-radius: 10px;
    background: #e7f2f0;
    color: #0b7771
}

.report-heading h2 {
    margin: 0;
    font-size: 17px;
    font-weight: 700;
    color: #1d3b37
}

.report-heading p {
    margin: 5px 0 0;
    color: #879692;
    font-size: 12px;
    line-height: 1.6
}

.report-quote {
    margin: 16px 0 0;
    padding: 10px 14px;
    border-left: 3px solid #b6d6d1;
    border-radius: 0 8px 8px 0;
    background: #f4f9f8;
    color: #3f615c;
    font-size: 13px;
    line-height: 1.6;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden
}

.report-field {
    position: relative;
    display: block;
    margin-top: 18px
}

.report-label {
    display: block;
    margin-bottom: 7px;
    color: #1d3b37;
    font-size: 13px;
    font-weight: 650
}

.report-label em {
    margin-left: 6px;
    color: #a4b4ae;
    font-size: 12px;
    font-style: normal;
    font-weight: 500
}

.report-select {
    position: relative
}

.report-select::after {
    content: '';
    position: absolute;
    top: 50%;
    right: 14px;
    width: 7px;
    height: 7px;
    margin-top: -5px;
    border-right: 2px solid #7d938d;
    border-bottom: 2px solid #7d938d;
    transform: rotate(45deg);
    pointer-events: none
}

.report-dialog select {
    width: 100%;
    padding: 11px 34px 11px 14px;
    border: 1px solid #d3ddda;
    border-radius: 8px;
    background: #fff;
    color: #1d3b37;
    font: inherit;
    font-size: 13px;
    font-weight: 650;
    appearance: none;
    cursor: pointer
}

.report-dialog select:focus-visible {
    outline: none;
    border-color: #0b7771;
    box-shadow: 0 0 0 3px rgba(11, 119, 113, .14)
}

.report-dialog textarea {
    display: block;
    width: 100%;
    padding: 11px 14px;
    border: 1px solid #d3ddda;
    border-radius: 8px;
    background: #fff;
    color: #1d3b37;
    font: inherit;
    font-size: 13px;
    line-height: 1.65;
    resize: vertical
}

.report-dialog textarea:focus-visible {
    outline: none;
    border-color: #0b7771;
    box-shadow: 0 0 0 3px rgba(11, 119, 113, .14)
}

.report-counter {
    position: absolute;
    right: 3px;
    bottom: -17px;
    color: #a4b4ae;
    font-size: 11px
}

.report-note {
    display: flex;
    align-items: center;
    gap: 6px;
    margin: 25px 0 0;
    color: #879692;
    font-size: 12px;
    line-height: 1.6
}

.report-note svg {
    flex: none;
    color: #b6574d
}

.report-actions {
    display: flex;
    justify-content: flex-end;
    gap: 10px;
    margin-top: 20px
}

.report-actions .button {
    padding: 9px 18px;
    font-size: 13px
}

.report-submit {
    background: #0b7771;
    border-color: #0b7771;
    color: #fff
}

.report-submit:hover:not(:disabled) {
    background: #08625d;
    border-color: #08625d
}

@media (max-width: 520px) {
    .report-dialog {
        padding: 22px 20px 18px
    }

    .report-actions {
        gap: 8px
    }

    .report-actions .button {
        flex: 1;
        padding: 10px 12px
    }
}
</style>
