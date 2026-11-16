<template>
  <div class="ann-manage">
    <div class="am-header">
      <div>
        <h2 class="am-title">
          公告管理
        </h2>
        <p class="am-sub">
          编辑的是工作副本，发布后生成版本快照（version+1）才对全员可见;改草稿不影响线上展示，需重新发布。
        </p>
      </div>
      <el-button
        :icon="Refresh"
        circle
        title="刷新"
        @click="load"
      />
    </div>

    <el-card
      v-loading="loading"
      class="am-card"
      shadow="never"
    >
      <template #header>
        <div class="am-card-head">
          <span>公告内容</span>
          <div class="am-meta">
            <el-tag
              v-if="draft"
              :type="statusType(draft.status)"
              size="small"
            >
              {{ statusLabel(draft.status) }}
            </el-tag>
            <span
              v-if="draft"
              class="am-ver"
            >当前 v{{ draft.version }}</span>
            <span
              v-if="draft?.publishedAt"
              class="am-time"
            >发布于 {{ formatTime(draft.publishedAt) }}</span>
          </div>
        </div>
      </template>

      <el-form
        label-position="top"
        @submit.prevent
      >
        <el-form-item label="标题">
          <el-input
            v-model="form.title"
            maxlength="255"
            show-word-limit
            placeholder="公告标题"
            :disabled="!canEdit"
          />
        </el-form-item>
        <el-form-item label="正文">
          <el-input
            v-model="form.body"
            type="textarea"
            :rows="8"
            placeholder="公告正文（支持多行）"
            :disabled="!canEdit"
          />
        </el-form-item>
      </el-form>

      <div
        v-if="canEdit"
        class="am-actions"
      >
        <el-button
          :loading="saving"
          @click="onSaveDraft"
        >
          保存草稿
        </el-button>
        <el-button
          type="primary"
          :loading="publishing"
          @click="onPublish"
        >
          发布
        </el-button>
        <el-button
          type="warning"
          plain
          :disabled="!draft || draft.status === 'ARCHIVED'"
          @click="onArchive"
        >
          归档
        </el-button>
      </div>
      <el-alert
        v-else
        type="info"
        :closable="false"
        title="当前账号仅有查看权限（notice:view），编辑发布需 notice:edit。"
      />
    </el-card>

    <el-card
      class="am-card"
      shadow="never"
    >
      <template #header>
        <span>发布历史</span>
      </template>
      <el-table
        :data="history"
        size="small"
        empty-text="暂无发布记录"
      >
        <el-table-column
          type="expand"
        >
          <template #default="{ row }">
            <div class="am-rev-body">
              {{ row.body }}
            </div>
          </template>
        </el-table-column>
        <el-table-column
          prop="version"
          label="版本"
          width="80"
        >
          <template #default="{ row }">
            v{{ row.version }}
          </template>
        </el-table-column>
        <el-table-column
          prop="title"
          label="标题"
          min-width="200"
          show-overflow-tooltip
        />
        <el-table-column
          prop="publishedBy"
          label="发布人"
          width="180"
          show-overflow-tooltip
        />
        <el-table-column
          label="发布时间"
          width="180"
        >
          <template #default="{ row }">
            {{ formatTime(row.publishedAt) }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card
      class="am-card"
      shadow="never"
    >
      <template #header>
        <div class="am-card-head">
          <span>迁移历史（撰写素材）</span>
          <el-button
            size="small"
            text
            @click="toggleSchema"
          >
            {{ showSchema ? '收起' : '展开' }}
          </el-button>
        </div>
      </template>
      <el-table
        v-if="showSchema"
        v-loading="schemaLoading"
        :data="schemaHistory"
        size="small"
        max-height="360"
        empty-text="暂无迁移记录"
      >
        <el-table-column
          prop="version"
          label="版本"
          width="90"
        />
        <el-table-column
          prop="description"
          label="说明"
          min-width="240"
          show-overflow-tooltip
        />
        <el-table-column
          label="安装时间"
          width="180"
        >
          <template #default="{ row }">
            {{ formatTime(row.installedOn) }}
          </template>
        </el-table-column>
        <el-table-column
          label="状态"
          width="90"
        >
          <template #default="{ row }">
            <el-tag
              :type="row.success ? 'success' : 'danger'"
              size="small"
            >
              {{ row.success ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
      <p
        v-else
        class="am-schema-hint"
      >
        只读展示最近 50 条 Flyway 迁移记录，供撰写「版本更新」类公告参考。
      </p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import {
  announcementApi,
  type AnnouncementDraft,
  type AnnouncementRevision,
  type SchemaHistoryRow
} from '@/api/announcement'

const auth = useAuthStore()
const canEdit = computed(() => auth.hasPermission('notice:edit'))

const draft = ref<AnnouncementDraft | null>(null)
const form = reactive({ title: '', body: '' })
const history = ref<AnnouncementRevision[]>([])
const schemaHistory = ref<SchemaHistoryRow[]>([])
const loading = ref(false)
const saving = ref(false)
const publishing = ref(false)
const showSchema = ref(false)
const schemaLoading = ref(false)

function statusLabel(status: string): string {
  if (status === 'PUBLISHED') return '已发布'
  if (status === 'ARCHIVED') return '已归档'
  return '草稿'
}

function statusType(status: string): 'success' | 'warning' | 'info' {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'ARCHIVED') return 'warning'
  return 'info'
}

function formatTime(ts: string | null): string {
  if (!ts) return '—'
  const t = Date.parse(ts)
  if (!t) return '—'
  return new Date(t).toLocaleString('zh-CN', { hour12: false })
}

async function load() {
  loading.value = true
  try {
    const [d, h] = await Promise.all([announcementApi.manage(), announcementApi.history()])
    draft.value = d
    form.title = d?.title ?? ''
    form.body = d?.body ?? ''
    history.value = h
  } finally {
    loading.value = false
  }
}

async function onSaveDraft() {
  if (!form.title.trim() || !form.body.trim()) {
    ElMessage.warning('标题与正文不能为空')
    return
  }
  saving.value = true
  try {
    await announcementApi.saveDraft({ title: form.title, body: form.body })
    ElMessage.success('草稿已保存')
    await load()
  } finally {
    saving.value = false
  }
}

async function onPublish() {
  if (!form.title.trim() || !form.body.trim()) {
    ElMessage.warning('标题与正文不能为空')
    return
  }
  try {
    await ElMessageBox.confirm(
      '发布后全员可见，版本号 +1 并生成历史快照。确认发布？',
      '发布公告',
      { type: 'warning', confirmButtonText: '发布', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  publishing.value = true
  try {
    // 先落草稿再发布，确保发布的是当前编辑内容
    await announcementApi.saveDraft({ title: form.title, body: form.body })
    await announcementApi.publish()
    ElMessage.success('已发布')
    await load()
  } finally {
    publishing.value = false
  }
}

async function onArchive() {
  try {
    await ElMessageBox.confirm('归档后公告条不再展示该公告，确认归档？', '归档公告', {
      type: 'warning',
      confirmButtonText: '归档',
      cancelButtonText: '取消'
    })
  } catch {
    return
  }
  await announcementApi.archive()
  ElMessage.success('已归档')
  await load()
}

async function toggleSchema() {
  showSchema.value = !showSchema.value
  if (showSchema.value && schemaHistory.value.length === 0) {
    schemaLoading.value = true
    try {
      schemaHistory.value = await announcementApi.schemaHistory()
    } finally {
      schemaLoading.value = false
    }
  }
}

onMounted(load)
</script>

<style scoped>
.ann-manage {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.am-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.am-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--ha-ink, #1A2233);
}
.am-sub {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: var(--ha-muted, #8B96AB);
  line-height: 1.5;
}
.am-card {
  border-radius: 12px;
}
.am-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.am-meta {
  display: flex;
  align-items: center;
  gap: 10px;
}
.am-ver,
.am-time {
  font-size: 12px;
  color: var(--ha-muted, #8B96AB);
}
.am-actions {
  display: flex;
  gap: 10px;
}
.am-rev-body {
  padding: 8px 16px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ha-ink-secondary, #4A5568);
  white-space: pre-wrap;
  word-break: break-word;
}
.am-schema-hint {
  margin: 0;
  font-size: 12.5px;
  color: var(--ha-muted, #8B96AB);
}
</style>