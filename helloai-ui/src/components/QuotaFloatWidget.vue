<template>
  <!--
    停靠形态：胶囊 Teleport 进个人工作台右栏头部（宿主由 Workbench 登记）。
    停靠态离开工作台页（宿主为空）不渲染任何形态；仅用户拖出后的悬浮态跨页显示——与站内消息铃铛同口径。
  -->
  <Teleport
    v-if="visible && quotaDockHost"
    :to="quotaDockHost"
  >
    <span
      v-if="mode === 'docked'"
      ref="dockPillRef"
      class="qw-pill qw-dock-pill"
      :class="{ 'is-open': expanded }"
      role="button"
      :aria-expanded="expanded"
      :title="expanded ? '点击收起' : 'LLM 额度（可拖出为悬浮浮球）'"
      @pointerdown="onPointerDown"
    >
      <span class="qw-total">{{ totalText }}</span>
      <span class="qw-dot" :class="dotClass" />
    </span>
    <span
      v-else
      class="qw-dock-ghost"
      title="浮球拖回此处停靠"
    >
      <span class="qw-ghost-mark">¥</span>
    </span>
  </Teleport>

  <Teleport to="body">
    <!-- 展开面板：两种形态共用（固定定位，不跟着宿主布局走） -->
    <div
      v-if="visible && expanded"
      class="quota-widget"
      :style="positionStyle"
    >
      <div
        ref="panelRef"
        class="qw-panel"
      >
        <!-- 头部整行可点收起（与浮球「点一下展开」对称，无需去找右上角 ×） -->
        <div
          class="qw-panel-head"
          title="点击收起"
          @click="onPanelCollapseClick"
        >
          <span class="qw-title">LLM 额度</span>
          <div class="qw-actions">
            <button
              v-if="canRefresh"
              type="button"
              class="qw-refresh"
              :disabled="loading"
              :title="loading ? '刷新中…' : '立即刷新'"
              @click.stop="refresh"
            >{{ loading ? '刷新中…' : '刷新' }}</button>
            <button
              type="button"
              class="qw-close"
              title="收起"
              @click.stop="collapsePanel"
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

        <!-- 底部「自动刷新 / 最后更新」整行也可点收起，与头部对称 -->
        <div
          class="qw-foot"
          title="点击收起"
          @click="onPanelCollapseClick"
        >
          <span class="qw-mode">{{ modeText }}</span>
          <span
            class="qw-fetch-hint"
            :title="lastFetchedText"
          >最后更新：{{ lastFetchedText }}</span>
        </div>
      </div>
    </div>

    <!-- 悬浮胶囊（折叠态） -->
    <div
      v-if="visible && effectiveMode === 'floating' && !expanded"
      class="quota-widget"
      :style="positionStyle"
      @pointerdown="onPointerDown"
    >
      <div
        class="qw-pill"
        role="button"
        :aria-expanded="expanded"
      >
        <span class="qw-total">
          {{ totalText }}
        </span>
        <span class="qw-dot" :class="dotClass" />
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { quotaApi, QUOTA_PROVIDER_LABELS, type QuotaSnapshot, type QuotaWidgetConfig } from '@/api/quota'
import { quotaDockHost, quotaDockVisible } from '@/composables/quotaDock'
import { isOverDock, usePointerDrag } from '@/composables/usePointerDrag'

/** 拖拽位置持久化键（仅悬浮态使用）。 */
const POS_KEY = 'quota.widget.pos'
/** 形态持久化键：docked=停靠工作台右栏（默认），floating=悬浮浮球；二选一不并存。 */
const MODE_KEY = 'quota.widget.mode'
/** 默认停靠位置：右下角，距视口右下缘。 */
const DEFAULT_POS = { x: 20, y: 20 }
const DEF_W = 260
const DEF_H = 240
/** 展开面板与视口边缘的安全间距（面板永不出可视窗口）。 */
const MARGIN = 10
/** 折叠态胶囊高度，用于推算浮球顶边位置（与 .qw-pill 的 height 保持同步）。 */
const PILL_H = 34
/** 展开面板宽度，用于水平 clamp（与 .qw-panel 的 width 保持同步）。 */
const PANEL_W = 264
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
/** 用户选择的形态；宿主不存在时会回落为悬浮（见 effectiveMode）。 */
const mode = ref<'docked' | 'floating'>(readMode())
/** 停靠胶囊元素：展开面板要以它的视口位置反推锚点。 */
const dockPillRef = ref<HTMLElement | null>(null)
/** 停靠锚点（right/bottom 距视口右/下缘 + 胶囊顶边），由 measureDock 刷新。 */
const dockRect = ref<{ right: number; bottom: number; pillTop: number } | null>(null)
/** 视口尺寸：展开态定位依赖它，resize 时更新以重新收敛。 */
const viewport = ref({ w: window.innerWidth, h: window.innerHeight })
/** 展开态面板元素，用于测量真实高度决定展开方向。 */
const panelRef = ref<HTMLElement | null>(null)
/** 展开方向：true=向上生长（默认，面板底边对齐浮球底边）；false=向下打开（浮球贴顶时）。 */
const openUp = ref(true)
/** 向下打开时的面板顶边（视口坐标），已 clamp 进可视窗口。 */
const openTop = ref(MARGIN)
let timer: ReturnType<typeof setInterval> | null = null
let clockTimer: ReturnType<typeof setInterval> | null = null
/** 拖拽起点锚点（right/bottom 距视口右/下缘）；按下时快照，拖动中按位移换算。 */
let dragAnchor = { right: 0, bottom: 0 }

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

function readMode(): 'docked' | 'floating' {
  try {
    return localStorage.getItem(MODE_KEY) === 'floating' ? 'floating' : 'docked'
  } catch {
    return 'docked'
  }
}

/** 切换形态并落地：拖出停靠位、拖回坑位都走这里。 */
function setMode(next: 'docked' | 'floating') {
  if (mode.value === next) return
  mode.value = next
  try {
    localStorage.setItem(MODE_KEY, next)
  } catch {
    /* ignore */
  }
}

/**
 * 形态收敛：停靠态必须有宿主（工作台右栏头部）才渲染；
 * 宿主为空说明不在工作台页 → 整件隐藏（与铃铛「停靠态不在其他页出现浮球」同口径）。
 * 只有用户拖出过的悬浮态才跨页显示。
 */
const effectiveMode = computed<'docked' | 'floating' | 'none'>(() => {
  if (mode.value === 'floating') return 'floating'
  return quotaDockHost.value ? 'docked' : 'none'
})

/** 离开工作台页（停靠态）时收起面板：锚点宿主已不存在，面板不应孤悬在其他页面。 */
watch(quotaDockHost, (host) => {
  if (!host && mode.value === 'docked' && expanded.value) expanded.value = false
})

/** 读停靠胶囊的真实视口位置：面板定位与「拖出切成悬浮」的起点都靠它。 */
function measureDock() {
  const el = dockPillRef.value
  if (!el) {
    dockRect.value = null
    return
  }
  const r = el.getBoundingClientRect()
  dockRect.value = {
    right: Math.max(0, window.innerWidth - r.right),
    bottom: Math.max(0, window.innerHeight - r.bottom),
    pillTop: r.top
  }
}

/** 当前锚点：停靠态用宿主实测值，悬浮态用持久化的 right/bottom 推算。 */
const anchor = computed(() => {
  if (effectiveMode.value === 'docked' && dockRect.value) return dockRect.value
  return {
    right: pos.value.x,
    bottom: pos.value.y,
    pillTop: viewport.value.h - pos.value.y - PILL_H
  }
})

/**
 * 定位：
 * - 折叠态：仅悬浮浮球用，以右下角为锚（right/bottom），与拖拽逻辑一致；
 * - 展开态：默认仍向上生长（面板底边对齐胶囊底边）；胶囊贴近视口顶部导致
 *   上方放不下时改为向下打开，并整体 clamp 进可视窗口 —— 头部与 × 永远可见。
 */
const positionStyle = computed(() => {
  if (!expanded.value) {
    return { right: pos.value.x + 'px', bottom: pos.value.y + 'px' }
  }
  const a = anchor.value
  // 水平：面板比胶囊宽，向右对齐时需保证左边缘不出界（胶囊被拖到左侧时右移贴边）
  const maxRight = Math.max(MARGIN, viewport.value.w - PANEL_W - MARGIN)
  const right = Math.max(MARGIN, Math.min(a.right, maxRight))
  if (openUp.value) {
    return { right: right + 'px', bottom: a.bottom + 'px' }
  }
  return { right: right + 'px', top: openTop.value + 'px' }
})

// 授权口径按动作码 quota:view（CODE_STYLE §43：不以角色代替授权判定）；平台开关关闭则不渲染
const visible = computed(() => auth.hasPermission('quota:view') && config.value.enabled)

/** 刷新是变更动作（POST /refresh/{key}），按 quota:edit 动作码独立判定。 */
const canRefresh = computed(() => auth.hasPermission('quota:edit'))

/** 工作台据此决定是否渲染停靠坑位：无权限或平台开关关闭时两边都不显示。 */
watch(visible, (v) => { quotaDockVisible.value = v }, { immediate: true })

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

/** 头部 / 底部整行点击收起；行内的刷新、× 按钮各自处理，不连带收起。 */
function onPanelCollapseClick(e: MouseEvent) {
  const target = e.target as HTMLElement | null
  if (target?.closest('button')) return
  collapsePanel()
}

/**
 * 决定展开方向并算出安全位置：
 * 以胶囊顶边为基准，若上方能完整容纳面板则向上生长，否则向下打开
 * （面板顶边对齐胶囊顶边，再 clamp 进视口），保证头部与关闭按钮始终可见。
 */
function reposition() {
  if (!expanded.value) return
  const vh = viewport.value.h
  const h = panelRef.value?.offsetHeight ?? DEF_H
  const pillTop = anchor.value.pillTop
  const spaceAbove = pillTop - MARGIN
  if (h <= spaceAbove) {
    openUp.value = true
    return
  }
  openUp.value = false
  const maxTop = Math.max(MARGIN, vh - h - MARGIN)
  openTop.value = Math.max(MARGIN, Math.min(pillTop, maxTop))
}

/** 展开：停靠态先测宿主位置，DOM 就绪后按真实高度收敛（面板高度随供应商条数变化）。 */
async function expand() {
  if (effectiveMode.value === 'docked') measureDock()
  expanded.value = true
  // 仅手动刷新模式不因展开触发外部请求，用户可显式点「刷新」
  if (autoRefreshSeconds.value > 0) fetchData()
  await nextTick()
  reposition()
}

const { onPointerDown } = usePointerDrag({
  canDrag: () => !expanded.value, // 面板展开时不整拖（轻点即收起）
  onPress: () => {
    dragAnchor = { right: pos.value.x, bottom: pos.value.y }
    // 停靠态：以胶囊实际位置为拖拽起点，拖出时不跳位
    if (effectiveMode.value === 'docked') {
      measureDock()
      if (dockRect.value) dragAnchor = { right: dockRect.value.right, bottom: dockRect.value.bottom }
    }
  },
  onDrag: (dx, dy) => {
    // 从停靠位拖出 → 切成悬浮，后续跟手都写 pos
    if (effectiveMode.value === 'docked') setMode('floating')
    pos.value.x = Math.max(4, Math.min(window.innerWidth - 70, dragAnchor.right - dx))
    // 定位用 bottom：向下拖（dy>0）应减小 bottom，故取减号（曾误写为 +dy 导致方向反转）
    pos.value.y = Math.max(4, Math.min(window.innerHeight - 60, dragAnchor.bottom - dy))
    persistPos()
  },
  onRelease: (e, dragged) => {
    if (!dragged) {
      // 轻点 = 开合面板（与悬浮铃铛一致：再点一次收回）
      if (expanded.value) collapsePanel()
      else void expand()
      return
    }
    // 拖回工作台停靠坑位（±DOCK_PAD 容差）即复原为停靠形态
    if (effectiveMode.value === 'floating' && isOverDock('[data-quota-dock]', e.clientX, e.clientY)) {
      setMode('docked')
    }
  }
})
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

/** 视口变化：折叠态拖拽边界与展开态方向都需重新收敛（停靠锚点也要重测）。 */
function onResize() {
  viewport.value = { w: window.innerWidth, h: window.innerHeight }
  if (expanded.value && effectiveMode.value === 'docked') measureDock()
  reposition()
}

/** 面板高度随供应商条数 / 失败文案变化：展开态需重新判方向，避免撑出视口。 */
watch(providers, () => {
  if (expanded.value) void nextTick(reposition)
})

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
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  stopPoll()
  stopClock()
  document.removeEventListener('visibilitychange', onVisibility)
  window.removeEventListener('resize', onResize)
})
</script>

<style scoped>
.quota-widget {
  position: fixed;
  z-index: 3000;
  user-select: none;
  font-family: var(--ha-font-family, inherit);
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
  /* 不透明实色（原用 --ha-content-bg，浅色主题该 token 为 #EAE7E71C，11% 透明度会透出下层内容） */
  background: var(--ha-surface-elevated, #FFFFFF);
  box-shadow: var(--ha-shadow-lg, 0 6px 20px rgba(0,0,0,.18));
  cursor: grab;
  touch-action: none;
  font-size: 13px;
  font-weight: 600;
  color: var(--ha-ink, #1A2233);
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

/* 停靠态：工作台右栏头部里的紧凑胶囊（拖出即变回悬浮浮球） */
.qw-dock-pill {
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  font-size: 12px;
  box-shadow: none;
  user-select: none;
}
.qw-dock-pill.is-open {
  border-color: var(--ha-primary, #7C3AED);
  color: var(--ha-primary, #7C3AED);
}
/* 悬浮时留下的停靠坑位：与余额胶囊同形的缩小版（全圆角、等宽量级），比方块更易识别为「拖回此处」 */
.qw-dock-ghost {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 28px;
  min-width: 76px;
  padding: 0 12px;
  border: 1px dashed var(--ha-border, rgba(128,128,128,.4));
  border-radius: 999px;
  color: var(--ha-muted, #8B96AB);
  font-size: 12px;
  user-select: none;
}
.qw-ghost-mark {
  line-height: 1;
  font-weight: 600;
}

/* 展开面板 */
.qw-panel {
  width: 264px;
  border-radius: 12px;
  border: 1px solid var(--ha-border, rgba(128,128,128,.3));
  /* 同胶囊：实色浮层底（--ha-surface-elevated 亮 #FFFFFF / 暗 #121828），不用半透明内容底 */
  background: var(--ha-surface-elevated, #FFFFFF);
  box-shadow: var(--ha-shadow-lg, 0 10px 30px rgba(0,0,0,.2));
  /* 极端矮视口兜底：面板自身滚动，不撑出可视窗口 */
  max-height: calc(100vh - 20px);
  overflow: hidden auto;
}
.qw-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--ha-border, rgba(128,128,128,.15));
  /* 整行可点收起，给出可点提示；sticky 保证面板内滚动时关闭仍可见 */
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--ha-surface-elevated, #FFFFFF);
  cursor: pointer;
}
.qw-panel-head:hover { background: var(--ha-surface-hover, rgba(0,0,0,.04)); }
.qw-title { font-size: 13px; font-weight: 700; color: var(--ha-ink, #1A2233); }
.qw-actions { display: flex; align-items: center; gap: 6px; }
.qw-refresh,
.qw-close {
  border: none;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: var(--ha-ink-secondary, #4A5568);
  padding: 2px 6px;
  border-radius: 6px;
}
.qw-refresh:hover:not(:disabled),
.qw-close:hover { background: var(--ha-surface-hover, rgba(0,0,0,.06)); }
.qw-refresh:disabled { opacity: .5; cursor: default; }

.qw-list { list-style: none; margin: 0; padding: 6px 0; }
.qw-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  font-size: 12.5px;
}
.qw-name { width: 74px; flex-shrink: 0; color: var(--ha-ink-secondary, #4A5568); }
/* 金额用主文本色（暗色 #EEF2F8 / 亮色 #1A2233），等宽数字提升可读性 */
.qw-value {
  font-weight: 600;
  color: var(--ha-ink, #1A2233);
  font-variant-numeric: tabular-nums;
}
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
  color: var(--ha-muted, #8B96AB);
  white-space: nowrap;
}
.qw-fail { color: #ef4444; }
.qw-src { margin-left: 4px; font-size: 10px; padding: 0 4px; border-radius: 4px; background: var(--ha-surface-hover, rgba(0,0,0,.06)); }
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
  color: var(--ha-muted, #8B96AB);
  /* 整行可点收起，与头部一致给出可点提示 */
  cursor: pointer;
}
.qw-foot:hover { background: var(--ha-surface-hover, rgba(0,0,0,.04)); }
.qw-mode {
  white-space: nowrap;
}
.qw-fetch-hint {
  white-space: nowrap;
}
</style>