<template>
  <el-dialog
    v-model="visible"
    title="管理 API Key（主 / 备）"
    width="860px"
    append-to-body
    @close="emit('update:modelValue', false)"
  >
    <div
      v-loading="loading"
      class="keys-panel"
    >
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="keys-alert"
      >
        <template #title>
          平台不启用自动故障切换：同一时刻只有标为「启用」的那一把参与调用。
        </template>
        <div class="keys-alert-body">
          切换 / 停用 / 删除都由你手动发起；停用或删除**启用位**时，会把**最旧的备位**自动顶为启用位，
          没有备位可顶则会被拒绝（避免该供应商静默变成「未配置」）。
        </div>
      </el-alert>

      <el-table
        :data="rows"
        size="small"
        border
        class="keys-table"
        empty-text="该供应商尚未配置任何 API Key"
      >
        <el-table-column
          label="状态"
          width="92"
        >
          <template #default="{ row }">
            <el-tag
              :type="statusTagType(row)"
              size="small"
              effect="light"
            >
              {{ statusLabel(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="Key"
          min-width="150"
        >
          <template #default="{ row }">
            <span
              v-if="row.masked"
              class="key-mono"
            >{{ row.masked }}</span>
            <span
              v-else
              class="key-absent"
            >—（无明文可脱敏）</span>
          </template>
        </el-table-column>
        <el-table-column
          label="指纹"
          min-width="230"
        >
          <template #default="{ row }">
            <span
              class="key-mono key-fingerprint"
              :title="row.fingerprint || ''"
            >{{ row.fingerprint || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="创建时间"
          width="150"
        >
          <template #default="{ row }">
            {{ formatTime(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="188"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'DISABLED'"
              size="small"
              type="primary"
              plain
              @click="handleActivate(row)"
            >
              设为启用
            </el-button>
            <el-button
              v-if="row.status === 'ACTIVE'"
              size="small"
              plain
              @click="handleDeactivate(row)"
            >
              停用
            </el-button>
            <el-button
              size="small"
              type="danger"
              plain
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="keys-hint">
        指纹 = 明文的 SHA-256 前缀（24 字符，不可逆），用于区分与点名多把 Key；
        它不会暴露明文，也不能由指纹反推 Key。
      </div>
    </div>

    <template #footer>
      <el-button
        :loading="loading"
        @click="load"
      >
        刷新
      </el-button>
      <el-button
        type="primary"
        @click="visible = false"
      >
        完成
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  settingsApi,
  type LlmProviderApiKeyItem,
  type LlmProviderApiKeyChange,
  type LlmProviderResponse
} from '@/api/settings'

const props = defineProps<{
  modelValue: boolean
  provider: LlmProviderResponse | null
}>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  /** 主备状态有任何变化时通知父级（父级据此刷新 Provider 列表上的脱敏值 / 是否已配置）。 */
  changed: []
}>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => emit('update:modelValue', v))

const rows = ref<LlmProviderApiKeyItem[]>([])
const loading = ref(false)

// 打开即加载；provider 变化时（弹窗复用）也重载
watch(visible, v => { if (v) load() })

async function load() {
  if (!props.provider) {
    rows.value = []
    return
  }
  loading.value = true
  try {
    rows.value = await settingsApi.listLlmProviderApiKeys(props.provider.id) || []
  } catch {
    ElMessage.error('加载 Key 列表失败')
    rows.value = []
  } finally {
    loading.value = false
  }
}

function statusLabel(row: LlmProviderApiKeyItem): string {
  if (row.status === 'ACTIVE') return '启用中'
  if (row.status === 'DISABLED') return '备位'
  return '已淘汰'
}

function statusTagType(row: LlmProviderApiKeyItem): 'success' | 'info' | 'warning' {
  if (row.status === 'ACTIVE') return 'success'
  if (row.status === 'DISABLED') return 'info'
  return 'warning'
}

/** 继承位（当前启用位之外还有多少把备位）——决定「停用 / 删除启用位」是否会触发顶替。 */
function standbyCount(): number {
  return rows.value.filter(r => r.status === 'DISABLED').length
}

/** 把「会触发顶替」的后果说清楚再让用户确认，而不是操作完才发现换了 Key。 */
function successionWarning(): string {
  const n = standbyCount()
  if (n === 0) {
    return '\n\n注意：当前没有备位可顶替，后端会拒绝本次操作。请先新增一把备位。'
  }
  return `\n\n本次会自动把最旧的一把备位（共 ${n} 把备位）顶为启用位。`
}

async function handleActivate(row: LlmProviderApiKeyItem) {
  try {
    await ElMessageBox.confirm(
      `将把该 Key 设为启用位，原启用位会降为备位。${row.masked ? `\n\n目标：${row.masked}` : ''}`,
      '切换启用位',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await run(() => settingsApi.activateLlmProviderApiKey(props.provider!.id, row.id),
    (r) => (r.activeChanged ? '已切换启用位' : '启用位未变化'))
}

async function handleDeactivate(row: LlmProviderApiKeyItem) {
  try {
    await ElMessageBox.confirm(
      `停用后该 Key 不再参与调用。${row.masked ? `\n\n目标：${row.masked}` : ''}${successionWarning()}`,
      '停用 Key',
      { type: 'warning' }
    )
  } catch {
    return
  }
  await run(() => settingsApi.deactivateLlmProviderApiKey(props.provider!.id, row.id),
    (r) => (r.activeChanged ? '已停用，并顶替了新的启用位' : '已停用'))
}

async function handleDelete(row: LlmProviderApiKeyItem) {
  const extra = row.status === 'ACTIVE' ? successionWarning() : ''
  try {
    await ElMessageBox.confirm(
      `删除后该 Key 不再出现在列表中（软删，审计记录仍保留）。${row.masked ? `\n\n目标：${row.masked}` : ''}${extra}`,
      '删除 Key',
      { type: 'warning', confirmButtonText: '删除' }
    )
  } catch {
    return
  }
  await run(() => settingsApi.deleteLlmProviderApiKey(props.provider!.id, row.id),
    (r) => (r.activeChanged ? '已删除，并顶替了新的启用位' : '已删除'))
}

/** 统一执行：成功后刷新列表 + 通知父级 + 提示；失败由请求层弹错，这里兜底一句。 */
async function run(
  action: () => Promise<LlmProviderApiKeyChange>,
  successText: (r: LlmProviderApiKeyChange) => string
) {
  if (!props.provider) return
  try {
    const result = await action()
    ElMessage.success(successText(result))
    await load()
    emit('changed')
  } catch {
    // 业务拒绝（如「唯一启用 Key 不可停用」）由统一异常出口弹可读错误，此处不覆盖
    await load()
  }
}

function formatTime(value?: string): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
    + `${pad(d.getHours())}:${pad(d.getMinutes())}`
}
</script>

<style scoped>
.keys-panel {
  min-height: 160px;
}
.keys-alert {
  margin-bottom: 12px;
}
.keys-alert-body {
  font-size: 12px;
  line-height: 1.6;
}
.keys-table {
  width: 100%;
}
.key-mono {
  font-family: var(--ha-font-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace);
  font-size: 13px;
  word-break: break-all;
}
.key-fingerprint {
  font-size: 12px;
  color: var(--ha-ink-secondary);
}
.key-absent {
  font-size: 12px;
  color: var(--ha-muted);
}
.keys-hint {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ha-muted);
}
</style>
