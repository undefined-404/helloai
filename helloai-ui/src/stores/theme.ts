import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 主题状态管理。
 * - 持久化键：localStorage['helloai-theme']，缺省 dark（与 index.html 内联脚本同约定）。
 * - index.html <head> 的内联脚本在 Vue 挂载前已按 localStorage 设置 html.dark 类（防 FOUC），
 *   store 初始化只做状态镜像；后续切换统一走 setTheme。
 */
export type Theme = 'dark' | 'light'

const KEY_THEME = 'helloai-theme'

function readStoredTheme(): Theme {
  const stored = localStorage.getItem(KEY_THEME)
  return stored === 'light' ? 'light' : 'dark'
}

function applyThemeClass(theme: Theme) {
  document.documentElement.classList.toggle('dark', theme === 'dark')
}

export const useThemeStore = defineStore('theme', () => {
  const theme = ref<Theme>(readStoredTheme())
  // 兜底对齐：防止 index.html 类与存储值不一致（如手动改了 localStorage）
  applyThemeClass(theme.value)

  function setTheme(next: Theme) {
    theme.value = next
    localStorage.setItem(KEY_THEME, next)
    applyThemeClass(next)
  }

  function toggleTheme() {
    setTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  return { theme, setTheme, toggleTheme }
})
