/**
 * Vue Router 4 中 route.query 的值类型是
 *   string | string[] | null | undefined
 * 直接 `as string` 会在参数缺失/数组时拿到 undefined / "a,b"，属于裸强转。
 *
 * 这个工具把"取一个字符串参数"封装成显式 null 守卫，避免散落业务代码再各自强转。
 */
import type { LocationQuery, LocationQueryValue } from 'vue-router'

/**
 * 从 LocationQuery 中取单值字符串参数。
 * - 缺失 / 数组 / 空字符串 → 返回 null（由调用方决定默认值）
 * - 出现多次的同名参数，仅取第一个出现的非空值
 */
export function queryString(
  query: LocationQuery,
  key: string
): string | null {
  const raw = query[key]
  if (raw == null) return null
  const list = Array.isArray(raw) ? raw : [raw]
  for (const item of list as LocationQueryValue[]) {
    if (typeof item === 'string' && item.length > 0) return item
  }
  return null
}

/**
 * 缺省值友好的版本：取不到就返回 fallback。
 */
export function queryStringOr(
  query: LocationQuery,
  key: string,
  fallback: string
): string {
  return queryString(query, key) ?? fallback
}