import { shallowRef } from 'vue'

/**
 * 额度浮球的停靠宿主（个人工作台右栏头部提供）。
 *
 * <p>浮球组件与工作台分处两棵树（前者挂 MainLayout、Teleport 到 body），用模块级 ref 解耦：
 * 工作台挂载时登记宿主元素，浮球据此把胶囊 Teleport 进头部；宿主为空即「不可停靠」，
 * 浮球自动回落为悬浮形态（离开工作台页时如此）。</p>
 */
export const quotaDockHost = shallowRef<HTMLElement | null>(null)

/** 额度浮球当前是否可见（权限 + 平台开关）：工作台据此决定要不要渲染停靠宿主。 */
export const quotaDockVisible = shallowRef(false)