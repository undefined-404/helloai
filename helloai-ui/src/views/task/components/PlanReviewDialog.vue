<template>
  <el-dialog
    v-model="visible"
    title="拆解草案审阅"
    width="960px"
    top="6vh"
    append-to-body
    @open="loadDrafts"
    @close="$emit('close')"
  >
    <div v-loading="loadingDrafts">
      <p style="font-size:14px;color:var(--ha-ink);margin:0 0 12px">
        任务「{{ task?.title }}」的 AI 拆解草案，确认后草案转正为待分配子任务并按配置自动分发。
      </p>
      <el-alert
        v-if="generating"
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom:12px"
        title="AI 正在后台拆解，草案生成中…"
        description="通常需要一段时间（几十秒到几分钟不等，视任务复杂程度而定），本页自动刷新等待；期间可关闭弹窗，稍后从任务列表「审阅草案」再进入。"
      />
      <el-table
        v-if="drafts.length"
        :data="drafts"
        border
        stripe
        size="small"
        style="width:100%"
      >
        <el-table-column
          type="index"
          label="#"
          width="48"
        />
        <el-table-column
          prop="title"
          label="标题"
          min-width="160"
          show-overflow-tooltip
        />
        <el-table-column
          prop="content"
          label="内容"
          min-width="220"
          show-overflow-tooltip
        />
        <el-table-column
          prop="deliverable"
          label="交付物"
          min-width="140"
          show-overflow-tooltip
        />
        <el-table-column
          prop="acceptance"
          label="验收标准"
          min-width="140"
          show-overflow-tooltip
        />
        <el-table-column
          label="优先级"
          width="80"
        >
          <template #default="{ row }">
            <el-tag
              v-if="row.priority"
              :type="priorityTagType(row.priority)"
              size="small"
            >
              {{ row.priority }}
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column
          label="依赖"
          width="110"
        >
          <template #default="{ row }">
            {{ fmtDepends(row.dependsOn) }}
          </template>
        </el-table-column>
        <el-table-column
          label="技能"
          min-width="120"
        >
          <template #default="{ row }">
            <template v-if="row.requiredSkills?.length">
              <el-tag
                v-for="s in row.requiredSkills"
                :key="s"
                size="small"
                type="primary"
                style="margin-right:4px"
              >
                {{ s }}
              </el-tag>
            </template>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column
          prop="constraints"
          label="约束"
          min-width="120"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.constraints || '-' }}
          </template>
        </el-table-column>
        <!-- G-011 不确定性申报列：压缩展示「N 项待确认 · M 项假设」，明细在编辑弹窗内逐条修订 -->
        <el-table-column
          label="不确定性"
          min-width="130"
        >
          <template #default="{ row }">
            {{ fmtUncertainties(row.uncertainties) }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="70"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              size="small"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-else-if="!loadingDrafts && !generating"
        :description="emptyText"
      />
    </div>
    <template #footer>
      <el-button @click="visible = false">
        取消
      </el-button>
      <el-button
        type="danger"
        plain
        :loading="rejecting"
        @click="handleReject"
      >
        拒绝重拆
      </el-button>
      <el-button
        type="primary"
        :loading="confirming"
        :disabled="!drafts.length"
        @click="handleConfirm"
      >
        确认并分发
      </el-button>
    </template>

    <!-- G-010 草案人工修订：技能指派 + 执行约束（可选编辑，空值放行） -->
    <el-dialog
      v-model="editVisible"
      :title="`修订草案：${editingRow?.title || ''}`"
      width="560px"
      append-to-body
    >
      <el-form label-width="80px">
        <el-form-item label="技能标签">
          <el-select
            v-model="editForm.requiredSkills"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入技能标签（留空=不指派）"
            style="width:100%"
          >
            <el-option
              v-for="s in skillOptions"
              :key="s"
              :label="s"
              :value="s"
            />
          </el-select>
          <div style="font-size:12px;color:var(--ha-ink-2);line-height:1.6;margin-top:4px">
            拆解时由 AI 从平台技能目录指派；人工修订不做目录强校验，执行侧按命中技能注入规范，未命中标签自动跳过。
          </div>
        </el-form-item>
        <el-form-item label="执行约束">
          <el-input
            v-model="editForm.constraints"
            type="textarea"
            :rows="3"
            placeholder="不许改的事（边界/红线）；COARSE 粒度建议必填，其余可空"
          />
        </el-form-item>
        <!-- G-011 不确定性申报逐条编辑：kind 分级 + note；未填 note 的条目保存时由服务端丢弃 -->
        <el-form-item label="不确定性">
          <div style="width:100%">
            <div
              v-for="(u, idx) in editForm.uncertainties"
              :key="idx"
              style="display:flex;gap:8px;margin-bottom:8px;align-items:flex-start"
            >
              <el-select
                v-model="u.kind"
                style="width:130px"
              >
                <el-option label="待确认" value="UNCONFIRMED" />
                <el-option label="假设" value="ASSUMPTION" />
              </el-select>
              <el-input
                v-model="u.note"
                placeholder="不确定性描述（如：目标表是否含已归档分区）"
              />
              <el-button
                link
                type="danger"
                @click="removeUncertainty(idx)"
              >
                删除
              </el-button>
            </div>
            <el-button
              size="small"
              @click="addUncertainty"
            >
              + 添加一项
            </el-button>
            <div style="font-size:12px;color:var(--ha-ink-2);line-height:1.6;margin-top:4px">
              假设（ASSUMPTION）执行侧可自行验证、推翻即上报；待确认（UNCONFIRMED）须先验证再动手，无法验证则 BLOCKED 上报。保存时未填描述的条目会被丢弃。
            </div>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">
          取消
        </el-button>
        <el-button
          type="primary"
          :loading="savingDraft"
          @click="saveEdit"
        >
          保存
        </el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { taskApi } from '@/api/task'
import { subTaskApi } from '@/api/subTask'
import type { Task, SubTask, LongId, Uncertainty } from '@/types'

const props = defineProps<{ modelValue: boolean; task: Task | null }>()
const emit = defineEmits<{ 'update:modelValue': [v: boolean]; close: []; done: [] }>()

const visible = ref(props.modelValue)
watch(() => props.modelValue, v => { visible.value = v })
watch(visible, v => {
  emit('update:modelValue', v)
  // 弹窗关闭即停轮询，避免后台定时器持续打接口
  if (!v) stopPolling()
})

const loadingDrafts = ref(false)
const confirming = ref(false)
const rejecting = ref(false)
const drafts = ref<SubTask[]>([])
// 拆解异步化：草案由后台生成，弹窗打开即进入轮询等待态
const generating = ref(false)
const emptyText = ref('未找到待审阅草案（可能为异常残留），建议「拒绝重拆」使任务回到待规划状态')

const POLL_INTERVAL_MS = 3000
const MAX_WAIT_MS = 5 * 60 * 1000
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollDeadline = 0

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
  generating.value = false
}

async function loadDrafts() {
  if (!props.task) return
  stopPolling()
  loadingDrafts.value = true
  drafts.value = []
  pollDeadline = Date.now() + MAX_WAIT_MS
  await pollOnce()
}

// 轮询单步：草案为空且任务仍 PLANNING 时继续等待；
// 任务已回退 PENDING（拆解失败/超时回收）则停止并提示重试
async function pollOnce() {
  const task = props.task
  if (!task || !visible.value) { stopPolling(); return }
  try {
    const [list, latest] = await Promise.all([
      taskApi.planDrafts(String(task.id)),
      taskApi.getById(String(task.id))
    ])
    if (!visible.value) return
    if (list.length) {
      drafts.value = list
      generating.value = false
      loadingDrafts.value = false
      return
    }
    if (latest?.status === 'PENDING') {
      generating.value = false
      loadingDrafts.value = false
      emptyText.value = '拆解失败，任务已回到待规划状态，请回任务列表重试'
      ElMessage.warning('拆解失败，请回任务列表重试')
      return
    }
    if (latest?.status !== 'PLANNING' || Date.now() >= pollDeadline) {
      generating.value = false
      loadingDrafts.value = false
      return
    }
    // 仍在拆解中：进入等待态，3s 后继续轮询
    generating.value = true
    pollTimer = setTimeout(pollOnce, POLL_INTERVAL_MS)
  } catch {
    // 拦截器已弹错；不继续轮询避免报错刷屏
    generating.value = false
    loadingDrafts.value = false
  }
}

onBeforeUnmount(stopPolling)

function priorityTagType(priority: string): 'danger' | 'warning' | 'info' {
  const p = priority.toUpperCase()
  if (p === 'HIGH' || p === 'P0') return 'danger'
  if (p === 'MEDIUM' || p === 'P1') return 'warning'
  return 'info'
}

// dependsOn 存草案 id，映射为表内序号展示（如「依赖 #1,#2」）
function fmtDepends(dependsOn?: LongId[] | null): string {
  if (!dependsOn?.length) return '-'
  const seqs = dependsOn
    .map(id => drafts.value.findIndex(d => String(d.id) === String(id)) + 1)
    .filter(seq => seq > 0)
  return seqs.length ? `依赖 #${seqs.join(',')}` : '-'
}

// G-011 不确定性压缩展示：「N 项待确认 · M 项假设」（全空时显示 -）
function fmtUncertainties(uncertainties?: Uncertainty[] | null): string {
  if (!uncertainties?.length) return '-'
  const pending = uncertainties.filter(u => u.kind === 'UNCONFIRMED').length
  const assumed = uncertainties.filter(u => u.kind === 'ASSUMPTION').length
  const parts: string[] = []
  if (pending > 0) parts.push(`${pending} 项待确认`)
  if (assumed > 0) parts.push(`${assumed} 项假设`)
  return parts.length ? parts.join(' · ') : `${uncertainties.length} 项`
}

// --- G-010 草案人工修订（技能指派 + 执行约束） ---
// --- G-011 扩展：不确定性逐条编辑（kind + note，空行保存时由服务端丢弃） ---
const editVisible = ref(false)
const savingDraft = ref(false)
const editingRow = ref<SubTask | null>(null)
const editForm = ref<{ requiredSkills: string[]; constraints: string; uncertainties: { kind: string; note: string }[] }>(
  { requiredSkills: [], constraints: '', uncertainties: [] }
)

// 技能下拉候选：全部草案已指派标签的并集（目录端点本批未暴露，allow-create 兜自由输入）
const skillOptions = computed(() => {
  const set = new Set<string>()
  drafts.value.forEach(d => d.requiredSkills?.forEach(s => set.add(s)))
  return [...set]
})

function openEdit(row: SubTask) {
  editingRow.value = row
  editForm.value = {
    requiredSkills: [...(row.requiredSkills ?? [])],
    constraints: row.constraints ?? '',
    uncertainties: (row.uncertainties ?? []).map(u => ({ kind: u.kind ?? 'UNCONFIRMED', note: u.note ?? '' }))
  }
  editVisible.value = true
}

// 新增一条默认「待确认」空行（note 留空保存时服务端丢弃）
function addUncertainty() {
  editForm.value.uncertainties.push({ kind: 'UNCONFIRMED', note: '' })
}

function removeUncertainty(idx: number) {
  editForm.value.uncertainties.splice(idx, 1)
}

async function saveEdit() {
  const row = editingRow.value
  if (!row) return
  savingDraft.value = true
  try {
    // 保存前过滤空行（与服务端空白 note 丢弃口径一致），kind 下拉限定两值无需强校验
    const uncertainties = editForm.value.uncertainties
      .filter(u => u.note.trim() !== '')
      .map(u => ({ kind: u.kind, note: u.note }))
    await subTaskApi.updateDraft(row.id, {
      requiredSkills: editForm.value.requiredSkills,
      constraints: editForm.value.constraints,
      uncertainties
    })
    // 本地同步，免整表重拉
    row.requiredSkills = [...editForm.value.requiredSkills]
    row.constraints = editForm.value.constraints
    row.uncertainties = uncertainties
    ElMessage.success('草案已修订')
    editVisible.value = false
  } catch { /* 拦截器已弹错 */ }
  finally { savingDraft.value = false }
}

async function handleConfirm() {
  if (!props.task) return
  try {
    await ElMessageBox.confirm(
      `将 ${drafts.value.length} 条草案转正为待分配子任务，任务进入「进行中」并按配置自动分配执行 Agent。是否继续？`,
      '确认拆解方案',
      { type: 'warning', confirmButtonText: '确认并分发', cancelButtonText: '取消' }
    )
  } catch { return }
  confirming.value = true
  try {
    await taskApi.confirmPlan(String(props.task.id))
    ElMessage.success(`已确认 ${drafts.value.length} 条草案并开始分发`)
    visible.value = false
    emit('done')
  } catch { /* 拦截器已弹错 */ }
  finally { confirming.value = false }
}

async function handleReject() {
  if (!props.task) return
  try {
    await ElMessageBox.confirm(
      '将作废全部草案，任务回到「待规划」状态，可重新触发 AI 拆解。是否继续？',
      '拒绝拆解方案',
      { type: 'warning', confirmButtonText: '拒绝重拆', cancelButtonText: '取消' }
    )
  } catch { return }
  rejecting.value = true
  try {
    const res = await taskApi.rejectPlan(String(props.task.id))
    ElMessage.success(`已作废 ${res.cancelledCount} 条草案，任务已回到待规划`)
    visible.value = false
    emit('done')
  } catch { /* 拦截器已弹错 */ }
  finally { rejecting.value = false }
}
</script>
