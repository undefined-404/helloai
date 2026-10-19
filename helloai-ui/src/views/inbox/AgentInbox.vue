<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>Agent 收件箱</span>
          <div>
            <!-- 未读筛选（管理端默认「全部」：admin 应能看所有，而不是只看未读） -->
            <el-radio-group
              v-model="readFilter"
              size="small"
              style="margin-right:8px"
              @change="onFilterChange"
            >
              <el-radio-button :value="null">
                全部
              </el-radio-button>
              <el-radio-button :value="0">
                未读
              </el-radio-button>
              <el-radio-button :value="1">
                已读
              </el-radio-button>
            </el-radio-group>
            <!-- 归档筛选只有两类（2026-10-18 修正）：「含归档」与「已归档」语义重复，
                 取消该中间态。默认「未归档」= 业务上待处理的消息。
                 注意：库中历史消息可能已全部过期归档，此时本页为空属预期，
                 空态会引导切换到「已归档」查看。 -->
            <el-radio-group
              v-model="archiveFilter"
              size="small"
              style="margin-right:8px"
              @change="onFilterChange"
            >
              <el-radio-button :value="0">
                未归档
              </el-radio-button>
              <el-radio-button :value="1">
                已归档
              </el-radio-button>
            </el-radio-group>
            <el-tag
              v-if="unreadCount > 0"
              type="danger"
              size="small"
            >
              未读 {{ unreadCount }}
            </el-tag>
            <el-button
              size="small"
              class="ml-sm"
              @click="load"
            >
              刷新
            </el-button>
          </div>
        </div>
      </template>
      <el-table
        v-loading="loading"
        :data="list"
        border
        stripe
        style="width:100%"
      >
        <el-table-column
          label="类型"
          width="120"
        >
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="eventTypeTag(row.eventType)"
              :title="eventTypeDesc(row.eventType)"
            >
              {{ eventTypeLabel(row.eventType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="所属 Agent"
          width="180"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            <span
              v-if="row.agentName"
              class="agent-name"
            >{{ row.agentName }}</span>
            <!-- Agent 已删除时后端 name 为 null：回退 ID 短尾，不显示「未知」 -->
            <span
              v-else
              class="agent-name is-missing"
            >…{{ String(row.agentId || '').slice(-6) }}</span>
          </template>
        </el-table-column>
        <!-- 阅读状态与归档状态拆成两列（2026-10-19）：原实现是二选一互斥标签，
             归档行永远只显示「已归档」，点「标为已读」后界面毫无变化 ⇒ 看起来像按钮失效。
             两个维度本就正交（已归档但未读是真实存在的状态），必须分开显示。 -->
        <el-table-column
          label="阅读"
          width="88"
        >
          <template #default="{ row }">
            <el-tag
              size="small"
              effect="light"
              :type="row.isRead === 1 ? 'info' : 'danger'"
            >
              {{ row.isRead === 1 ? '已读' : '未读' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          label="归档"
          width="96"
        >
          <template #default="{ row }">
            <el-tag
              size="small"
              effect="light"
              :type="row.isArchived === 1 ? 'info' : 'success'"
            >
              {{ row.isArchived === 1 ? '已归档' : '未归档' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="title"
          label="标题"
          min-width="240"
          show-overflow-tooltip
        />
        <el-table-column
          label="优先级"
          width="80"
        >
          <template #default="{ row }">
            <el-tag
              v-if="row.priority==='URGENT'"
              size="small"
              type="danger"
            >
              紧急
            </el-tag>
            <el-tag
              v-else-if="row.priority==='HIGH'"
              size="small"
              type="warning"
            >
              高
            </el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column
          label="时间"
          width="170"
        >
          <template #default="{ row }">
            {{ fmtTime(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          :width="ACTION.TWO"
          fixed="right"
        >
          <template #default="{ row }">
            <div class="action-cell">
              <!-- 置灰而非隐藏（2026-10-19）：隐藏会让每行按钮位置随状态跳动，
                   实测「点了没反应」的一半原因就是按钮直接消失、用户以为没点中。
                   禁用态保留占位，状态一目了然。 -->
              <el-button
                size="small"
                :disabled="row.isRead === 1 || !!acting[String(row.id)]"
                @click="markRead(row)"
              >
                标为已读
              </el-button>
              <el-button
                size="small"
                :disabled="row.isArchived === 1 || !!acting[String(row.id)]"
                @click="markArchived(row)"
              >
                归档
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="!list.length && !loading"
        :description="emptyText"
        :image-size="70"
      />
      <!-- 分页（2026-10-18）：原实现写死 limit，53 条只显示前 20 条且无法翻页 -->
      <el-pagination
        v-if="total > 0"
        layout="total, sizes, prev, pager, next, jumper"
        background
        :total="total"
        :page-size="pageSize"
        :current-page="page"
        :page-sizes="[20, 50, 100]"
        style="margin-top:12px;justify-content:center"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { inboxApi, type InboxRow } from '@/api/inbox'
import { useEnumCatalogStore } from '@/stores/enumCatalog'
import { ACTION } from '@/utils/tableConfig'
import { fmtTime } from '@/utils/tableConfig'

const list = ref<InboxRow[]>([])
const loading = ref(false)
const unreadCount = ref(0)
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
// 未读筛选：null=全部（管理端默认看全部，才符合「admin 能看所有」的预期）
const readFilter = ref<number | null>(null)
// 归档筛选：只有两类（未归档 0 / 已归档 1）。默认「未归档」= 待处理消息。
// ★库中历史消息可能已全部过期归档，此时默认页为空属预期（空态会引导切换）。
const archiveFilter = ref<number>(0)

// 枚举字典（2026-10-18）：事件类型中文名由后端 /api/enums/catalog 下发，
// 不再依赖前端硬编码的 INBOX_EVENT_TYPE_MAP（后者只覆盖 8 个键，后端实际发 10 种，
// 导致 sub_task.approved 等裸露）。
/** 空态文案：区分「真的没有」与「只是被筛选掉了」（避免又被误判成功能坏了）。 */
const emptyText = computed(() => {
  if (archiveFilter.value === 0) {
    return '当前没有未归档的消息（历史消息已过期归档，切换到「已归档」可查看）'
  }
  return readFilter.value === 0 ? '没有未读消息' : '暂无已归档消息'
})

const enumStore = useEnumCatalogStore()
function eventTypeLabel(code: string): string {
  enumStore.ensureLoaded() // fire-and-forget：就绪后重渲染生效
  return enumStore.labelOf('inboxEventType', code)
}
function eventTypeDesc(code: string): string {
  return enumStore.descOf('inboxEventType', code)
}

const eventTagMap: Record<string, string> = {
  'sub_task.assigned': '', 'sub_task.submitted': 'warning',
  'sub_task.rejected': 'danger', 'sub_task.blocked': 'danger',
  'sub_task.paused': 'info', 'sub_task.resumed': 'success', 'sub_task.cancelled': 'info'
}
function eventTypeTag(type: string) { return eventTagMap[type] || '' }

async function load() {
  loading.value = true
  try {
    const res = await inboxApi.list({
      page: page.value,
      pageSize: pageSize.value,
      isRead: readFilter.value ?? undefined,
      // 归档三态：null=全部（不传），0=未归档，1=已归档
      isArchived: archiveFilter.value ?? undefined
    })
    list.value = res?.list || []
    total.value = Number(res?.total) || 0
    const c = await inboxApi.count()
    unreadCount.value = c?.total_unread || 0
  } finally { loading.value = false }
}

function onFilterChange() {
  page.value = 1
  load()
}

function onPageChange(p: number) {
  page.value = p
  load()
}

// 切换每页条数：回到第一页，避免新 pageSize 下越界
function onSizeChange(sz: number) {
  pageSize.value = sz
  page.value = 1
  load()
}
// 正在提交的行 id → true：按行独立防连点，跨行可并发。
// 用对象而不是单个字符串，否则两行同时操作时先完成的 finally 会清掉另一行的禁用态。
const acting = ref<Record<string, boolean>>({})

async function markRead(row: InboxRow) {
  const key = String(row.id)
  if (row.isRead === 1 || acting.value[key]) return
  acting.value[key] = true
  try {
    await inboxApi.markRead(row.id)
    // 只改本地行状态，不 reload：当前筛选若为「未读」，reload 会让整行消失，
    // 用户反而看不出「已读」这个结果。让标签就地变灰更能证明操作生效。
    row.isRead = 1
    unreadCount.value = Math.max(0, unreadCount.value - 1)
    ElMessage.success('已标记为已读')
  } finally {
    delete acting.value[key]
  }
}

async function markArchived(row: InboxRow) {
  const key = String(row.id)
  if (row.isArchived === 1 || acting.value[key]) return
  acting.value[key] = true
  try {
    await inboxApi.markArchived(row.id)
    ElMessage.success('已归档')
    // 归档必须 reload：筛选「未归档」时该行本就应该离开列表。
    // 筛选「已归档」时不会调用本按钮（已置灰），故不存在无谓刷新。
    await load()
  } finally {
    delete acting.value[key]
  }
}
onMounted(() => load())
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.ml-sm { margin-left: var(--ha-space-sm); }
.agent-name { color: var(--el-text-color-primary); }
.agent-name.is-missing { color: var(--ha-muted); }
</style>
