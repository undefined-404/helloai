import { ref } from 'vue'
import { ElMessage } from 'element-plus'

/**
 * 带降级的剪贴板写入。
 *
 * 浏览器 Clipboard API（navigator.clipboard.writeText）只在以下环境可用：
 *   - HTTPS 页面
 *   - localhost / 127.0.0.1
 *   - file://
 * 在普通 HTTP 部署下会直接 reject，函数会回退到 `document.execCommand('copy')`
 * 配合隐藏 textarea；如果仍失败，返回 false 让调用方自行决定降级展示策略。
 *
 * 用法：
 *   const { copy } = useClipboardWithFallback()
 *   const ok = await copy('要复制的文本')
 *   if (!ok) ElMessage.warning('复制失败，请手动复制')
 */
export function useClipboardWithFallback() {
  const fallback = ref(false) // 当前是否处于降级路径（仅做诊断用）

  async function copy(text: string): Promise<boolean> {
    if (!text) return false
    // 1. 优先使用标准 Clipboard API
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(text)
        fallback.value = false
        return true
      }
    } catch {
      // 进入降级路径
    }

    // 2. 降级方案：隐藏 textarea + execCommand('copy')
    try {
      const textarea = document.createElement('textarea')
      textarea.value = text
      textarea.setAttribute('readonly', '')
      textarea.style.position = 'fixed'
      textarea.style.top = '-9999px'
      textarea.style.left = '-9999px'
      document.body.appendChild(textarea)
      textarea.select()
      textarea.setSelectionRange(0, textarea.value.length)
      const ok = document.execCommand('copy')
      document.body.removeChild(textarea)
      fallback.value = true
      return ok
    } catch {
      fallback.value = true
      return false
    }
  }

  return { copy, fallback }
}

/**
 * 便捷封装：复制成功用 ElMessage 提示，失败给可手选文本提示。
 * 给已经在用 ElMessage 的页面省一层 try/catch。
 */
export async function copyTextWithToast(text: string, successMsg = '已复制', failMsg = '复制失败，请手动复制') {
  const { copy } = useClipboardWithFallback()
  const ok = await copy(text)
  if (ok) {
    ElMessage.success(successMsg)
  } else {
    ElMessage.error(failMsg)
  }
  return ok
}