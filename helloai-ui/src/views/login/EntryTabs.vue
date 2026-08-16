<template>
  <div
    class="entry-tabs"
    role="tablist"
    aria-label="访问入口"
  >
    <button
      type="button"
      role="tab"
      class="entry-tab"
      @click="$emit('select', 'login')"
    >
      登录
    </button>
    <button
      type="button"
      role="tab"
      class="entry-tab"
      @click="$emit('select', 'register')"
    >
      注册
    </button>
  </div>
</template>

<script setup lang="ts">
// 入口选择：登录 / 注册
// 单一职责：仅当父组件 entryMode === null 时渲染，点击后 emit 选择回父组件
export type EntryMode = 'login' | 'register'

defineEmits<{
  'select': [mode: EntryMode]
}>()
</script>

<style scoped>
.entry-tabs {
  display: flex;
  gap: 8px;
  width: 100%;
}

.entry-tab {
  flex: 1;
  height: 38px;
  border: 1px solid var(--login-accent-border);
  border-radius: var(--ha-radius-md);
  background: rgba(255, 255, 255, 0.04);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  color: rgba(255, 255, 255, 0.72);
  font-size: 13px;
  font-weight: 600;
  font-family: var(--ha-font-family);
  cursor: pointer;
  box-shadow: none;
  transition: all var(--ha-duration-fast) var(--ha-ease-out);
}

.entry-tab:hover {
  border-color: var(--login-accent-border-strong);
  background: rgba(124, 58, 237, 0.14);
  color: #FFFFFF;
  transform: translateY(-1px);
}

.entry-tab.active {
  border-color: rgba(196, 181, 253, 0.35);
  background: rgba(124, 58, 237, 0.78);
  color: #FFFFFF;
  box-shadow: 0 6px 18px rgba(124, 58, 237, 0.40);
}
</style>
