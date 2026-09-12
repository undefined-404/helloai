// ============================================================
// 动态路由组件映射（BASE-1.3）
// 菜单树接口下发 component（相对 src/views，不含扩展名），
// 此处用 import.meta.glob 预扫描全部页面生成映射表，按字符串懒加载。
// Vite 不支持运行时变量动态 import，必须走 glob 静态映射。
// ============================================================

/** 预扫描 src/views 下全部 .vue，key 形如 /src/views/system/UserList.vue */
const viewModules = import.meta.glob('/src/views/**/*.vue')

/**
 * 按 component 字符串取懒加载器。
 *
 * @param component 形如 'system/UserList'（相对 src/views，不含扩展名）
 * @returns 懒加载函数；映射缺失时告警并返回 undefined（调用方跳过注册）
 */
export function lazyLoad(component: string): (() => Promise<unknown>) | undefined {
  const normalized = component.replace(/^\/+/, '').replace(/\.vue$/, '')
  const loader = viewModules[`/src/views/${normalized}.vue`]
  if (loader) {
    return loader
  }
  console.warn(`[route] 组件映射缺失，跳过路由注册: ${component}`)
  return undefined
}
