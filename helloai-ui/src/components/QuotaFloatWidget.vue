<template>
  <Teleport to="body">
    <!--
      G-026 LLM 额度浮球（设计 §4.6）。
      双重闸门：quota:view（primary，无权限不渲染）+ sys_config quota.widget.enabled 平台开关
      （secondary，显式 '0' 才关闭；读取失败 fail-open 为开启，默认 '1'）。
      自动刷新间隔由 sys_config quota.refresh.interval-seconds 决定（0=仅手动刷新）。
    -->
    <div
      v-if="visible"
      class="quota-widget"
      :class="{ expanded }"
      :style="positionStyle"
      @pointerdown="onPointerDown"
    >
      <!-- 折叠态：总额 + 状态色点（整体可拖，轻点切换展开） -->
      <div
        v-if="!expanded"
        class="qw-pill"
        role="button"
        :aria-expanded="expanded"
      >
        <span class="qw-total">
          {{ totalText }}
        </span>
        <span class="qw-dot" :class="dotClass" />
      </div>

      <!-- 展开态面板 -->
      <div
        v-else
        class="qw-panel"
        @pointerdown.stop
      >
        <div class="qw-panel-head">
          <span class="qw-title">LLM 额度</span>
          <div class="qw-actions">
            <button
              v-if="canRefresh"
              type="button"
              class="qw-refresh"
              :disabled="loading"
              :title="loading ? '刷新中…' : '立即刷新'"
              @click="refresh"
            >{{ loading ? '刷新中…' : '刷新' }}</button>
            <button
              type="button"
              class="qw-close"
              title="收起"
              @click="collapsePanel"
            >×</button>
          </div>
        </div>

        <ul class="qw-list">
          <li
            v-for="p in providers"
            :key="p.providerKey"
            class="qw-row"
            :class="{ failed: !p.available }"
            :title="p.error || ''"
          >
            <span class="qw-name">{{ p.label }}</span>

            <!-- 金额型 -->
            <template v-if="p.quotaPercent == null">
              <span class="qw-value amount">{{ amountText(p) }}</span>
            </template>

            <!-- 额度型（MiniMax：百分比 + 进度条，不强行换算金额） -->
            <template v-else>
              <span class="qw-value quota">
                <span class="qw-quota-val">剩余 {{ fmtPercent(p.quotaPercent) }}</span>
                <span class="qw-bar">
                  <span
                    class="qw-bar-fill"
                    :class="barClass(p.quotaPercent)"
                    :style="{ width: clampedPercent(p.quotaPercent) + '%' }"
                  />
                </span>
              </span>
            </template>

            <span class="qw-meta">
              {{ metaText(p) }}
              <span v-if="p.available" class="qw-src" :class="p.source.toLowerCase()">
                {{ srcText(p.source) }}
              </span>
              <span v-if="!p.available" class="qw-fail">{{ p.error || '获取失败' }}</span>
            </span>
          </li>
        </ul>

        <div class="qw-foot">
          <span class="qw-mode">{{ modeText }}</span>
          <span
            class="qw-fetch-hint"
            :title="lastFetchedText"
          >最后更新：{{ lastFetchedText }}</span>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { quotaApi, QUOTA_PROVIDER_LABELS, type QuotaSnapshot, type QuotaWidgetConfig } from '@/api/quota'

/** 拖拽位置持久化键。 */
const POS_KEY = 'quota.widget.pos'
/** 默认停靠位置：右下角，距视口右下缘。 */
const DEFAULT_POS = { x: 20, y: 20 }
const DEF_W = 260
const DEF_H = 240
/** 配置读取失败时的缺省：开启 + 60s（与后端缺省一致，fail-open）。 */
const DEFAULT_CONFIG: QuotaWidgetConfig = { enabled: true, refreshIntervalSeconds: 60 }
/** 相对时间文案的刷新周期（与轮询解耦，手动模式下也能持续更新「几分钟前」）。 */
const CLOCK_MS = 30000

const auth = useAuthStore()

const config = ref<QuotaWidgetConfig>({ ...DEFAULT_CONFIG })
const expanded = ref(false)
const loading = ref(false)
const data = ref<QuotaSnapshot[]>([])
const lastFetchedAt = ref<number>(0)
const nowTick = ref(Date.now())
const pos = ref({ ...DEFAULT_POS })
let timer: ReturnType<typeof setInterval> | null = null
let clockTimer: ReturnType<typeof setInterval> | null = null
let dragged = false
let dragStart = { x: 0, y: 0, px: 0, py: 0 }

const providers = computed(() =>
  data.value.map((s) => ({ ...s, label: QUOTA_PROVIDER_LABELS[s.providerKey] || s.providerKey }))
)

/** 折叠态总额 = 仅金额型可用供应商求和（MiniMax 百分比不掺入金额）。 */
const totalAmount = computed(() => {
  let sum = 0
  for (const p of providers.value) {
    if (p.available && p.quotaPercent == null && p.amount != null) {
      sum += Number(p.amount)
    }
  }
  return sum
})

const totalText = computed(() => {
  if (providers.value.length === 0) return '—'
  if (totalAmount.value === 0) {
    // 全无金额型可用 → 若至少一家额度型可用，则显示「已连接」而非 0（不伪报 ¥0）
    return providers.value.some((p) => p.available) ? '已连接' : '—'
  }
  return '¥ ' + totalAmount.value.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
})

/** 状态色点：全可用=绿；部分失败=琥珀；全失败/无数据=灰。 */
const dotClass = computed(() => {
  if (providers.value.length === 0) return 'off'
  const failedCount = providers.value.filter((p) => !p.available).length
  if (failedCount === 0) return 'ok'
  if (failedCount < providers.value.length) return 'warn'
  return 'err'
})

const lastFetchedText = computed(() => {
  if (!lastFetchedAt.value) return '—'
  const diff = Math.max(0, Math.floor((nowTick.value - lastFetchedAt.value) / 1000))
  if (diff < 10) return '刚刚'
  if (diff < 60) return diff + ' 秒前'
  const m = Math.floor(diff / 60)
  if (m < 60) return m + ' 分钟前'
  return Math.floor(m / 60) + ' 小时前'
})

/** 0=仅手动刷新：不轮询、打开面板不自动抓，只在点「刷新」时查一次。 */
const autoRefreshSeconds = computed(() => config.value.refreshIntervalSeconds)

const modeText = computed(() => autoRefreshSeconds.value > 0
  ? `自动刷新：每 ${intervalLabel(autoRefreshSeconds.value)}`
  : '自动刷新已关闭')

const positionStyle = computed(() => ({
  right: pos.value.x + 'px',
  bottom: pos.value.y + 'px'
}))

// 授权口径按动作码 quota:view（CODE_STYLE §43：不以角色代替授权判定）；平台开关关闭则不渲染
const visible = computed(() => auth.hasPermission('quota:view') && config.value.enabled)

/** 刷新是变更动作（POST /refresh/{key}），按 quota:edit 动作码独立判定。 */
const canRefresh = computed(() => auth.hasPermission('quota:edit'))

function amountText(p: QuotaSnapshot & { label: string }): string {
  if (!p.available || p.amount == null) return '—'
  const n = Number(p.amount)
  return (p.currency && p.currency !== 'CNY' ? p.currency + ' ' : '¥ ') +
    n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

function metaText(p: QuotaSnapshot & { label: string }): string {
  if (!p.available) return ''
  // 金额型：抓取时间；额度型：已有独立时间显示，这里留空避免重复
  return p.quotaPercent == null ? (p.fetchedAt ? relTime(p.fetchedAt) : '') : ''
}

function srcText(source: string): string {
  return { LIVE: '实时', CACHED: '缓存', FAILED: '失败' }[source] || source
}

function relTime(ts: number | string): string {
  const t = typeof ts === 'string' ? Date.parse(ts) : ts
  if (!t) return ''
  const diff = Math.max(0, Math.floor((Date.now() - t) / 1000))
  if (diff < 10) return '刚刚'
  if (diff < 60) return diff + ' 秒前'
  const m = Math.floor(diff / 60)
  if (m < 60) return m + ' 分钟前'
  return Math.floor(m / 60) + ' 小时前'
}

function fmtPercent(v: number | string): string {
  const n = Number(v)
  if (Number.isNaN(n)) return '—'
  return Math.round(n) + '%'
}

function intervalLabel(seconds: number): string {
  if (seconds >= 3600) return '1 小时'
  return Math.round(seconds / 60) + ' 分钟'
}

function clampedPercent(v: number | string): number {
  const n = Number(v)
  if (Number.isNaN(n)) return 0
  return Math.max(0, Math.min(100, n))
}

function barClass(v: number | string): string {
  const n = Number(v)
  if (Number.isNaN(n)) return 'ok'
  if (n <= 20) return 'low'   // 快没了
  if (n <= 50) return 'mid'
  return 'ok'
}

/**
 * 读取运行期配置（quota:view 保护）：
 * 开关默认开启，自动刷新间隔默认 60s；读取失败（403/网络）fail-open 为可用。
 */
async function loadConfig() {
  try {
    const cfg = await quotaApi.widgetConfig()
    config.value = {
      enabled: cfg.enabled !== false,
      refreshIntervalSeconds: Number.isFinite(cfg.refreshIntervalSeconds)
        ? cfg.refreshIntervalSeconds
        : DEFAULT_CONFIG.refreshIntervalSeconds
    }
  } catch {
    config.value = { ...DEFAULT_CONFIG }
  }
}

/** 「最后更新」取快照自身的抓取时刻（含缓存原时刻），而不是本次请求时刻。 */
function syncFetchedAt(list: QuotaSnapshot[]) {
  let latest = 0
  for (const s of list) {
    const t = typeof s.fetchedAt === 'string' ? Date.parse(s.fetchedAt) : Number(s.fetchedAt)
    if (Number.isFinite(t) && t > latest) latest = t
  }
  if (latest > 0) lastFetchedAt.value = latest
}

async function fetchData() {
  if (loading.value) return
  loading.value = true
  try {
    const list = await quotaApi.overview()
    data.value = list
    syncFetchedAt(list)
  } catch {
    // 拉取整体失败：保留旧数据/空态，不伪报
  } finally {
    loading.value = false
  }
}

/** 仅手动刷新模式：打开页面只读最近落库快照，不触网。 */
async function fetchCached() {
  if (loading.value) return
  loading.value = true
  try {
    const list = await quotaApi.overviewCached()
    data.value = list
    syncFetchedAt(list)
  } catch {
    // 读取失败：保留空态，不伪报
  } finally {
    loading.value = false
  }
}

/**
 * 手动刷新：走单家 refresh 端点（跳过后端 60s 缓存），不能复用 overview——
 * overview 命中缓存时点「刷新」拿到的仍是旧值。列表为空时退回 overview 建立初始数据。
 */
async function refresh() {
  if (loading.value) return
  loading.value = true
  try {
    const list = data.value.length === 0
      ? await quotaApi.overview()
      : await Promise.all(data.value.map((p) => quotaApi.refresh(p.providerKey)))
    data.value = list
    syncFetchedAt(list)
  } catch {
    // 刷新整体失败：保留旧数据，不伪报
  } finally {
    loading.value = false
  }
}

/** 展开面板内「收起」按钮用：直接收起，不触发刷新（§4.6：隐藏时不轮询）。 */
function collapsePanel() {
  expanded.value = false
}

function onPointerDown(e: PointerEvent) {
  if (expanded.value) return // 面板内不整拖
  pressed = true
  dragged = false
  dragStart = { x: e.clientX, y: e.clientY, px: pos.value.x, py: pos.value.y }
}

function onPointerMove(e: PointerEvent) {
  if (!pressed || expanded.value) return
  const dx = e.clientX - dragStart.x
  const dy = e.clientY - dragStart.y
  if (Math.abs(dx) > 3 || Math.abs(dy) > 3) dragged = true
  if (!dragged) return
  const vw = window.innerWidth
  const vh = window.innerHeight
  pos.value.x = Math.max(4, Math.min(vw - 70, dragStart.px - dx))
  // 定位用 bottom：向下拖（dy>0）应减小 bottom，故取减号（曾误写为 +dy 导致方向反转）
  pos.value.y = Math.max(4, Math.min(vh - 60, dragStart.py - dy))
  persistPos()
}

function onPointerUp() {
  if (!pressed) return
  pressed = false
  if (expanded.value) return
  if (!dragged) {
    // 非拖拽 = 轻点 → 展开（订阅 pointerup，避免点击态与拖拽冲突）
    expanded.value = true
    // 仅手动刷新模式不因展开触发外部请求，用户可显式点「刷新」
    if (autoRefreshSeconds.value > 0) fetchData()
  }
  dragged = false
}

let pressed = false
function persistPos() {
  try {
    localStorage.setItem(POS_KEY, JSON.stringify(pos.value))
  } catch {
    /* ignore */
  }
}

function startPoll() {
  stopPoll()
  const seconds = autoRefreshSeconds.value
  if (seconds <= 0) return
  timer = setInterval(() => {
    // 页面可见 + 持 quota:view + 开关开启才轮询（设计 §4.6：隐藏时不轮询）
    if (visible.value && document.visibilityState === 'visible') {
      fetchData()
    }
  }, seconds * 1000)
}

function stopPoll() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function startClock() {
  if (clockTimer) return
  clockTimer = setInterval(() => {
    nowTick.value = Date.now()
  }, CLOCK_MS)
}

function stopClock() {
  if (clockTimer) {
    clearInterval(clockTimer)
    clockTimer = null
  }
}

async function onVisibility() {
  if (document.visibilityState !== 'visible') return
  // 设置可能在其他标签页被改过：回到前台重读，轮询节奏随之切换
  await loadConfig()
  if (!visible.value) {
    stopPoll()
    stopClock()
    return
  }
  startClock()
  if (autoRefreshSeconds.value > 0) {
    fetchData()
    startPoll()
  } else {
    stopPoll()
    fetchCached()
  }
}

onMounted(async () => {
  try {
    const saved = localStorage.getItem(POS_KEY)
    if (saved) pos.value = { ...DEFAULT_POS, ...JSON.parse(saved) }
  } catch { /* ignore */ }
  // 先读配置再决定是否发请求：开关关 → 不渲染也不发请求（Q-8 口径）
  await loadConfig()
  if (visible.value) {
    startClock()
    if (autoRefreshSeconds.value > 0) {
      fetchData()
      startPoll()
    } else {
      // 仅手动刷新：只展示最近落库快照，不触网
      fetchCached()
    }
  }
  document.addEventListener('visibilitychange', onVisibility)
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
})

onBeforeUnmount(() => {
  stopPoll()
  stopClock()
  document.removeEventListener('visibilitychange', onVisibility)
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
})
</script>

<style scoped>
.quota-widget {
  position: fixed;
  z-index: 3000;
  user-select: none;
  font-family: var(--ha-font, inherit);
}
.quota-widget.expanded {
  /* 展开面板随内容自适应，避免拖拽定位框过大 */
  transform: scale(1);
}

/* 折叠态胶囊 */
.qw-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid var(--ha-border, rgba(128,128,128,.3));
  background: var(--ha-content-bg, #fff);
  box-shadow: var(--ha-shadow-lg, 0 6px 20px rgba(0,0,0,.18));
  cursor: grab;
  touch-action: none;
  font-size: 13px;
  font-weight: 600;
  color: var(--ha-text-primary, #222);
  white-space: nowrap;
}
.qw-total { line-height: 1; }
.qw-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.qw-dot.ok   { background: #22c55e; box-shadow: 0 0 6px #22c55e; }
.qw-dot.warn { background: #f59e0b; box-shadow: 0 0 6px #f59e0b; }
.qw-dot.err  { background: #ef4444; box-shadow: 0 0 6px #ef4444; }
.qw-dot.off  { background: #9ca3af; }

/* 展开面板 */
.qw-panel {
  width: 264px;
  border-radius: 12px;
  border: 1px solid var(--ha-border, rgba(128,128,128,.3));
  background: var(--ha-content-bg, #fff);
  box-shadow: var(--ha-shadow-lg, 0 10px 30px rgba(0,0,0,.2));
  overflow: hidden;
}
.qw-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--ha-border, rgba(128,128,128,.15));
}
.qw-title { font-size: 13px; font-weight: 700; }
.qw-actions { display: flex; align-items: center; gap: 6px; }
.qw-refresh,
.qw-close {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: var(--ha-text-secondary, #666);
  padding: 2px 6px;
  border-radius: 6px;
}
.qw-refresh:hover:not(:disabled),
.qw-close:hover { background: var(--ha-hover, rgba(0,0,0,.06)); }
.qw-refresh:disabled { opacity: .5; cursor: default; }

.qw-list { list-style: none; margin: 0; padding: 6px 0; }
.qw-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  font-size: 12.5px;
}
.qw-name { width: 74px; flex-shrink: 0; color: var(--ha-text-secondary, #666); }
.qw-value { font-weight: 600; color: var(--ha-text-primary, #222); }
.qw-value.amount { min-width: 62px; }
.qw-value.quota { flex: 1; display: flex; flex-direction: column; gap: 3px; }
.qw-quota-val { font-size: 12px; }
.qw-bar {
  height: 5px;
  border-radius: 3px;
  background: var(--ha-border, rgba(128,128,128,.25));
  overflow: hidden;
}
.qw-bar-fill { display: block; height: 100%; border-radius: 3px; }
.qw-bar-fill.ok   { background: #22c55e; }
.qw-bar-fill.mid  { background: #f59e0b; }
.qw-bar-fill.low  { background: #ef4444; }
.qw-meta {
  margin-left: auto;
  text-align: right;
  font-size: 11px;
  color: var(--ha-text-secondary, #999);
  white-space: nowrap;
}
.qw-fail { color: #ef4444; }
.qw-src { margin-left: 4px; font-size: 10px; padding: 0 4px; border-radius: 4px; background: var(--ha-hover, rgba(0,0,0,.06)); }
.qw-src.live   { color: #22c55e; }
.qw-src.cached { color: #f59e0b; }
.qw-src.failed { color: #ef4444; }

.qw-row.failed .qw-value { color: #9ca3af; }

.qw-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 12px 8px;
  border-top: 1px solid var(--ha-border, rgba(128,128,128,.15));
  font-size: 11px;
  color: var(--ha-text-secondary, #999);
}
.qw-mode {
  white-space: nowrap;
}
.qw-fetch-hint {
  white-space: nowrap;
}
</style>