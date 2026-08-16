<template>
  <el-dialog
    :model-value="modelValue"
    :title="content.title"
    width="440px"
    class="support-dialog"
    append-to-body
    destroy-on-close
    @update:model-value="(v: boolean) => $emit('update:modelValue', v)"
  >
    <p class="support-dialog-intro">
      {{ content.intro }}
    </p>
    <ol class="support-dialog-list">
      <li
        v-for="item in content.steps"
        :key="item"
      >
        {{ item }}
      </li>
    </ol>

    <div
      v-if="content.template"
      class="support-template"
    >
      <div class="support-template-header">
        <span>联系模板</span>
        <button
          type="button"
          class="helper-link"
          @click="$emit('copy-template')"
        >
          复制模板
        </button>
      </div>
      <pre>{{ content.template }}</pre>
    </div>

    <template #footer>
      <div class="support-dialog-footer">
        <el-button
          v-if="kind === 'password' && showGotoSetup"
          type="primary"
          plain
          @click="$emit('goto-setup')"
        >
          前往初始化
        </el-button>
        <el-button @click="$emit('update:modelValue', false)">
          知道了
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
// 找回密码 / 联系管理员 对话框
// 状态可见性 / 模板内容 / 跳转 setup 按钮都通过 props 传入，子组件只做渲染与事件透传

export type SupportKind = 'password' | 'contact'

export interface SupportContent {
  title: string
  intro: string
  steps: string[]
  template: string
}

defineProps<{
  modelValue: boolean
  kind: SupportKind
  content: SupportContent
  /** 当 kind === 'password' 且当前未初始化时，footer 显示「前往初始化」按钮 */
  showGotoSetup: boolean
}>()

defineEmits<{
  'update:modelValue': [value: boolean]
  'copy-template': []
  'goto-setup': []
}>()
</script>

<style scoped>
.support-dialog-intro {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--ha-muted);
}

.support-dialog-list {
  margin: 0;
  padding-left: 18px;
  color: var(--ha-ink-secondary);
  font-size: 13px;
  line-height: 1.7;
}

.support-template {
  margin-top: 16px;
  border: 1px solid var(--ha-border-light);
  border-radius: var(--ha-radius-lg);
  background: var(--ha-surface);
  padding: 14px 16px;
}

.support-template-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ha-ink);
}

.support-template pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: var(--ha-font-family);
  font-size: 12px;
  line-height: 1.6;
  color: var(--ha-ink-secondary);
}

.support-dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.helper-link {
  border: none;
  background: none;
  padding: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--login-accent-ink);
  cursor: pointer;
  transition: color var(--ha-duration-fast) var(--ha-ease-out),
              opacity var(--ha-duration-fast) var(--ha-ease-out);
}

.helper-link:hover {
  color: var(--login-accent-start);
}
</style>
