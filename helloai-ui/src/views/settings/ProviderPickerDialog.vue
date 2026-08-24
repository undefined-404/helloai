<template>
  <el-dialog
    v-model="visible"
    title="添加模型"
    width="560px"
    append-to-body
    @close="emit('update:modelValue', false)"
  >
    <div
      class="picker-grid"
      role="listbox"
      aria-label="选择 LLM 供应商"
    >
      <button
        v-for="p in PROVIDER_CATALOG"
        :key="p.providerCode"
        type="button"
        class="picker-card"
        role="option"
        :aria-label="'选择 ' + p.providerName"
        @click="pick(p)"
      >
        <span
          class="picker-badge"
          :style="{ background: p.brandColor }"
        >{{ p.monogram }}</span>
        <span class="picker-info">
          <span class="picker-name">{{ p.providerName }}</span>
          <span class="picker-tagline">{{ p.tagline }}</span>
        </span>
        <el-icon class="picker-arrow">
          <ArrowRight />
        </el-icon>
      </button>
    </div>
    <div class="picker-footer-hint">
      选择供应商后填写类型与 API 密钥；也可在下一步选择「自定义供应商」接入任意 OpenAI / Anthropic 兼容端点。
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ArrowRight } from '@element-plus/icons-vue'
import { PROVIDER_CATALOG, type CatalogProvider } from './providerCatalog'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  pick: [entry: CatalogProvider]
}>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

function pick(entry: CatalogProvider) {
  visible.value = false
  emit('pick', entry)
}
</script>

<style scoped>
/* 供应商双列网格：卡片式按钮，monogram 徽标 + 名称 + 右箭头 */
.picker-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.picker-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 14px;
  background: var(--ha-surface);
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-md);
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
  transition: border-color 0.15s ease, background 0.15s ease, transform 0.15s ease;
}
.picker-card:hover,
.picker-card:focus-visible {
  border-color: var(--ha-primary);
  background: var(--ha-surface-hover);
  outline: none;
}
.picker-card:active {
  transform: scale(0.985);
}
.picker-badge {
  flex: 0 0 auto;
  width: 38px;
  height: 38px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--ha-radius-sm);
  border: 1px solid rgba(255, 255, 255, 0.10);
  color: #fff;
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.5px;
}
.picker-info {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.picker-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ha-ink);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.picker-tagline {
  font-size: 12px;
  color: var(--ha-ink-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.picker-arrow {
  flex: 0 0 auto;
  color: var(--ha-ink-secondary);
  font-size: 14px;
}
.picker-card:hover .picker-arrow {
  color: var(--ha-primary);
}
.picker-footer-hint {
  margin-top: 14px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ha-ink-secondary);
}
</style>
