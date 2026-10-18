/**
 * 轮询失败退避：连续失败时按 2 的幂放大间隔，封顶 `maxMs`；任一轮成功即复位为基准间隔。
 *
 * <p>为什么需要：页面上多处「在途状态轮询」用固定间隔（如 5s）。一旦后端异常 / 网络中断 /
 * 网关 502，固定间隔会把「稳定的低频探测」变成「持续打点」，既刷满浏览器连接、
 * 又让后端在故障期间承受额外压力（故障放大）。退避让探测在连续失败时自然稀疏下来。</p>
 *
 * <p>复位语义：<b>只要任意一轮拿到有效响应就复位</b>（`failStreak = 0`），不做「半开」状态机——
 * 轮询是幂等只读探测，不是熔断器，多打一次的代价远小于漏掉一次状态收敛。</p>
 *
 * @param baseMs     基准间隔（毫秒），正常情况就是它
 * @param failStreak 连续失败次数（0 = 无失败）
 * @param maxMs      退避上限（毫秒），缺省 60s
 */
export function nextPollDelay(baseMs: number, failStreak: number, maxMs = 60_000): number {
  if (failStreak <= 0) return baseMs
  // 指数放大但限制指数，避免大数溢出 / 无意义的长等待；上限由 maxMs 兜住
  const amplified = baseMs * 2 ** Math.min(failStreak, 5)
  return Math.min(amplified, Math.max(baseMs, maxMs))
}
