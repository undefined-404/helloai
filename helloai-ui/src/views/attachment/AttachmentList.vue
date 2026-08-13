<template>
  <div class="page ha-entrance-up">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>附件管理</span>
          <el-button size="small" type="primary" @click="load">刷新</el-button>
        </div>
      </template>
      <div v-loading="loading" class="browser">
        <el-breadcrumb separator="/" class="crumb">
          <el-breadcrumb-item>
            <a class="crumb-link" @click="backTo(0)">全部附件</a>
          </el-breadcrumb-item>
          <el-breadcrumb-item v-for="(c, i) in crumbs" :key="i">
            <a class="crumb-link" @click="backTo(i + 1)">{{ c.name }}</a>
          </el-breadcrumb-item>
        </el-breadcrumb>
        <div v-for="(it, i) in view" :key="i" class="row" :class="{ file: it.kind === 'file' }" @click="it.kind === 'dir' ? enter(it) : downloadFile(it.attachment)">
          <el-icon v-if="it.kind === 'dir'" class="icon dir"><Folder /></el-icon>
          <el-icon v-else class="icon"><Document /></el-icon>
          <template v-if="it.kind === 'dir'">
            <span class="name">{{ it.name }}</span>
            <span v-if="it.idHint" class="hint">{{ it.idHint }}</span>
            <span class="meta">{{ it.count }} 个附件</span>
          </template>
          <template v-else>
            <span class="name" :title="it.attachment.storageUrl || it.attachment.fileName">{{ it.attachment.fileName }}</span>
            <el-tag size="small" :type="it.attachment.status === 'ACTIVE' ? 'success' : 'info'" class="tag">{{ it.attachment.status }}</el-tag>
            <span class="meta">{{ it.attachment.fileType }}</span>
            <span class="meta">{{ formatSize(it.attachment.fileSize) }}</span>
            <span class="meta time">{{ fmtTime(it.attachment.createTime) }}</span>
            <el-button size="small" type="primary" :loading="downloadingId === it.attachment.id" @click.stop="downloadFile(it.attachment)">下载</el-button>
          </template>
        </div>
        <el-empty v-if="!view.length && !loading" description="当前目录无内容" />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { Folder, Document } from '@element-plus/icons-vue'
import { attachmentApi } from '@/api/attachment'
import { saveBlobResponse } from '@/utils/download'
import { fmtTime } from '@/utils/tableConfig'
import type { Attachment } from '@/types'

interface DirItem {
  kind: 'dir'
  key: string
  name: string
  idHint?: string
  count: number
}
interface FileItem {
  kind: 'file'
  attachment: Attachment
}
type Item = DirItem | FileItem

const rows = ref<Attachment[]>([])
const crumbs = ref<{ key: string; name: string }[]>([])
const loading = ref(false)
const downloadingId = ref<null | string>(null)

function storageOf(att: Attachment): 'minio' | 'local' {
  return (att.storageUrl || '').startsWith('minio://') ? 'minio' : 'local'
}
function segsOf(att: Attachment): string[] {
  return (att.objectKey || '').split('/').filter(Boolean)
}
// 任务/子任务段回显标题，其余层级原样显示段名
function segmentName(seg: string, att: Attachment): { name: string; idHint?: string } {
  if (att.taskId && seg === String(att.taskId) && att.taskTitle) {
    return { name: att.taskTitle, idHint: 'ID ' + seg }
  }
  if (seg === String(att.subTaskId) && att.subTaskTitle) {
    return { name: att.subTaskTitle, idHint: 'ID ' + seg }
  }
  return { name: seg }
}

const view = computed<Item[]>(() => {
  if (!crumbs.value.length) {
    const count = { minio: 0, local: 0 }
    for (const att of rows.value) count[storageOf(att)]++
    return (['minio', 'local'] as const)
      .filter(k => count[k] > 0)
      .map(k => ({
        kind: 'dir' as const,
        key: k,
        name: k === 'minio' ? 'MinIO 附件' : '本地附件',
        count: count[k],
      }))
  }
  const storage = crumbs.value[0].key
  const depth = crumbs.value.length - 1
  const path = crumbs.value.slice(1).map(c => c.key)
  const dirs = new Map<string, DirItem>()
  const files: FileItem[] = []
  for (const att of rows.value) {
    if (storageOf(att) !== storage) continue
    const segs = segsOf(att)
    if (segs.length < depth + 1) continue
    const match = path.every((s, i) => segs[i] === s)
    if (!match) continue
    if (segs.length === depth + 1) {
      files.push({ kind: 'file', attachment: att })
    } else {
      const seg = segs[depth]
      let dir = dirs.get(seg)
      if (!dir) {
        const parsed = segmentName(seg, att)
        dir = { kind: 'dir', key: seg, name: parsed.name, idHint: parsed.idHint, count: 0 }
        dirs.set(seg, dir)
      }
      dir.count++
    }
  }
  const dirList = [...dirs.values()].sort((a, b) => a.name.localeCompare(b.name, 'zh'))
  files.sort((a, b) => a.attachment.fileName.localeCompare(b.attachment.fileName, 'zh'))
  return [...dirList, ...files]
})

function enter(dir: DirItem) {
  crumbs.value.push({ key: dir.key, name: dir.name })
}
function backTo(index: number) {
  crumbs.value = crumbs.value.slice(0, index)
}

async function load() {
  loading.value = true
  try {
    rows.value = await attachmentApi.list()
    crumbs.value = []
  } finally { loading.value = false }
}

function formatSize(bytes: number): string {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

async function downloadFile(att?: Attachment) {
  if (!att) return
  downloadingId.value = String(att.id)
  try {
    const resp = await attachmentApi.download(att.id)
    saveBlobResponse(resp, att.fileName || 'attachment')
  } catch { /* 拦截器已弹错 */ }
  finally { downloadingId.value = null }
}

onMounted(() => load())
</script>

<style scoped>
.page { max-width: var(--ha-content-width); }
.browser { min-height: 200px; }
.crumb { margin-bottom: 14px; }
.crumb-link { color: var(--ha-primary, #409eff); cursor: pointer; }
.row { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: 6px; cursor: pointer; }
.row:hover { background: var(--ha-primary-muted, rgba(124, 58, 237, 0.08)); }
.row.file { cursor: default; }
.icon { color: var(--ha-muted); font-size: 16px; flex-shrink: 0; }
.icon.dir { color: var(--ha-primary, #409eff); }
.name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.hint { font-size: 12px; color: var(--ha-muted); }
.meta { font-size: 12px; color: var(--ha-muted); min-width: 44px; text-align: right; }
.meta.time { min-width: 110px; }
.tag { margin-right: 4px; }
</style>
