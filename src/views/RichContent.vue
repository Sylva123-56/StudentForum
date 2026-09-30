<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ content: string }>()
type Block = { kind: 'paragraph' | 'heading' | 'code' | 'formula'; text: string; level?: number; id?: string }
const blocks = computed<Block[]>(() => {
  const result: Block[] = [], lines = props.content.split(/\r?\n/)
  let code: string[] = [], formula: string[] = [], mode: 'text' | 'code' | 'formula' = 'text'
  for (const line of lines) {
    if ((line.trim().startsWith('~~~') || line.trim().startsWith(String.fromCharCode(96, 96, 96))) && mode !== 'formula') { if (mode === 'code') { result.push({ kind: 'code', text: code.join('\n') }); code = []; mode = 'text' } else if (mode === 'text') mode = 'code'; continue }
    if (line.trim() === '$$') { if (mode === 'formula') { result.push({ kind: 'formula', text: formula.join('\n') }); formula = []; mode = 'text' } else if (mode === 'text') mode = 'formula'; continue }
    if (mode === 'code') { code.push(line); continue }
    if (mode === 'formula') { formula.push(line); continue }
    const heading = /^(#{1,3})\s+(.+)$/.exec(line)
    if (heading) result.push({ kind: 'heading', text: heading[2], level: heading[1].length, id: 'section-' + result.length })
    else if (line.trim()) result.push({ kind: 'paragraph', text: line })
  }
  if (code.length) result.push({ kind: 'code', text: code.join('\n') })
  if (formula.length) result.push({ kind: 'formula', text: formula.join('\n') })
  return result
})
const headings = computed(() => blocks.value.filter(block => block.kind === 'heading'))
</script>
<template>
  <div class="rich-content">
    <nav v-if="headings.length > 1" class="rich-toc"><strong>目录</strong><a v-for="heading in headings" :key="heading.id"
        :href="'#' + heading.id">{{ heading.text }}</a></nav><template v-for="(block, index) in blocks" :key="index">
      <h2 v-if="block.kind === 'heading' && block.level === 1" :id="block.id">{{ block.text }}</h2>
      <h3 v-else-if="block.kind === 'heading'" :id="block.id">{{ block.text }}</h3>
      <pre v-else-if="block.kind === 'code'"><code>{{ block.text }}</code></pre>
      <div v-else-if="block.kind === 'formula'" class="rich-formula">{{ block.text }}</div>
      <p v-else>{{ block.text }}</p>
    </template>
  </div>
</template>
