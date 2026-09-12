import type { Directive, DirectiveBinding } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 按钮级权限指令 v-auth（BASE-1.5）。
 *
 * 用法：`<el-button v-auth="'user:add'">新增</el-button>`
 * 无权限时从 DOM 移除元素；支持字符串或字符串数组（任一命中即显示）。
 */
export const auth: Directive<HTMLElement, string | string[]> = {
  mounted(el: HTMLElement, binding: DirectiveBinding<string | string[]>) {
    const { value } = binding
    if (!value) return
    const authStore = useAuthStore()
    const codes = Array.isArray(value) ? value : [value]
    const permitted = codes.some((code) => authStore.hasPermission(code))
    if (!permitted) {
      el.parentNode?.removeChild(el)
    }
  }
}
