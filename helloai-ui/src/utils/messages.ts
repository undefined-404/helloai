/**
 * 统一错误/成功消息字典。
 *
 * 目标：避免在业务代码里散落硬编码中文字符串，使文案后续可统一做 i18n / 改写时只动一处。
 *
 * 用法：
 *   import { msg } from '@/utils/messages'
 *   ElMessage.error(msg.login.requiredPassword)
 *
 * 命名约定：
 *   - 模块前缀：login / password / task / subtask / dashboard / common
 *   - 末尾描述：required / failed / success / canceled 等
 *   - 形参占位使用 {name} 占位，调用方用 format(template, ...args) 拼接
 */
export const msg = {
  common: {
    networkError: '网络错误，请稍后重试',
    canceled: '操作已取消',
    loadFailed: (name: string) => `加载${name}失败`,
    operationFailed: (name: string) => `${name}失败`,
    operationSuccess: (name: string) => `${name}成功`
  },
  login: {
    usernameRequired: '请输入管理员用户名',
    passwordRequired: '请输入登录密码',
    success: '登录成功',
    failed: '登录失败，请检查账号或凭证'
  },
  password: {
    currentRequired: '请输入当前密码',
    newRequired: '请输入新密码',
    minLength: '新密码至少 6 位',
    mismatch: '两次输入的新密码不一致',
    success: '密码已更新，请重新登录',
    failed: '修改密码失败'
  },
  copy: {
    success: (name = '') => (name ? `${name}已复制` : '已复制'),
    failed: '复制失败，请手动复制'
  },
  dashboard: {
    loadFailed: '加载仪表盘数据失败，请稍后重试'
  },
  subtask: {
    backfillFailed: '回填失败',
    backfillSuccess: (count: number) => `回填完成，共 ${count} 个任务`,
    redispatchSuccess: '重新指派成功',
    reassignSuccess: '已重新调度，子任务重新进入分发链',
    pausedSuccess: '已暂停',
    resumedSuccess: '已恢复',
    claimedSuccess: '认领成功'
  },
  task: {
    republishSuccess: '已重新发布并通知 PLANNER',
    planSuccess: '拆解完成，请审阅草案'
  }
} as const

/**
 * 简单占位符格式化："{name} 已 {state}" → "Foo 已 完成"
 */
export function format(template: string, ...args: string[]): string {
  return template.replace(/\{(\d+)\}/g, (_m, idx) => args[Number(idx)] ?? '')
}