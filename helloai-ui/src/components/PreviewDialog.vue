<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="85%"
    top="4vh"
    destroy-on-close
    @close="handleClose"
  >
    <div
      v-loading="loading"
      class="preview-wrap"
    >
      <!-- 二进制：图片 / PDF — 浏览器原生渲染，保持 iframe 通道 -->
      <iframe
        v-if="blobUrl"
        :src="blobUrl"
        class="preview-iframe"
      />
      <!-- Markdown — 走项目 MarkdownView，自动继承主题色 -->
      <div
        v-else-if="mode === 'markdown' && textContent"
        class="preview-md"
      >
        <MarkdownView :content="textContent" />
      </div>
      <!-- 纯文本 / 源码 — pre + 等宽字体，深色背景浅色字 -->
      <pre
        v-else-if="mode === 'text' && textContent"
        class="preview-pre"
      >{{ textContent }}</pre>
      <el-empty
        v-else-if="!loading"
        description="暂无预览内容"
      />
    </div>
    <template #footer>
      <el-button @click="handleDownload">
        下载原文件
      </el-button>
      <el-button
        type="primary"
        @click="handleClose"
      >
        关闭
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref, watch, computed, onBeforeUnmount } from 'vue'
import { attachmentApi } from '@/api/attachment'
import { saveBlobResponse } from '@/utils/download'
import MarkdownView from '@/components/MarkdownView.vue'
import type { Attachment } from '@/types'

type PreviewMode = 'iframe' | 'markdown' | 'text'

const props = defineProps<{
  /** v-model 可见性 */
  modelValue: boolean
  /** 当前预览的附件（modelValue=true 时必填） */
  attachment: Attachment | null
}>()

const emit = defineEmits<{
  'update:modelValue': [boolean]
}>()

const visible = computed<boolean>({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const loading = ref(false)
const blobUrl = ref('')
const textContent = ref('')
const mode = ref<PreviewMode>('iframe')

const title = computed<string>(() => props.attachment
  ? `预览：${props.attachment.fileName}`
  : '预览')

/**
 * 按后缀判定渲染通道：
 * - markdown（.md）：项目 MarkdownView 渲染（深色主题、DOMPurify 安全清洗）
 * - text（.txt/.log/.json/.xml/.yml/.yaml/.csv/.html/.htm/.svg + JS/TS 源码）：<pre> 等宽显示源码
 * - iframe（图片 / PDF）：浏览器原生渲染，主题背景透出
 */
function previewModeOf(fileName?: string | null): PreviewMode {
  if (!fileName) return 'iframe'
  const dot = fileName.lastIndexOf('.')
  if (dot < 0 || dot === fileName.length - 1) return 'iframe'
  const ext = fileName.slice(dot + 1).toLowerCase()
  if (ext === 'md') return 'markdown'
  if (
    ext === 'txt' || ext === 'log' ||
    ext === 'json' || ext === 'xml' ||
    ext === 'yml' || ext === 'yaml' || ext === 'csv' ||
    ext === 'html' || ext === 'htm' || ext === 'svg' ||
    // JS / TS 家族源码 — 与后端 AttachmentServiceImpl.detectContentTypeByName 镜像同步
    ext === 'js' || ext === 'mjs' || ext === 'cjs' || ext === 'jsx' ||
    ext === 'ts' || ext === 'tsx'
  ) return 'text'
  return 'iframe'
}

// 弹窗打开时按 mode 分流拉取内容；关闭时及时释放 URL 避免内存泄漏
watch(visible, (v) => {
  if (v && props.attachment) {
    void loadPreview()
  } else {
    cleanup()
  }
})

async function loadPreview() {
  if (!props.attachment) return
  loading.value = true
  blobUrl.value = ''
  textContent.value = ''
  const nextMode = previewModeOf(props.attachment.fileName)
  mode.value = nextMode
  try {
    if (nextMode === 'iframe') {
      const resp = await attachmentApi.previewById(props.attachment.id)
      const mime = (resp.headers['content-type'] as string | undefined) || 'application/octet-stream'
      blobUrl.value = URL.createObjectURL(new Blob([resp.data], { type: mime }))
    } else {
      const resp = await attachmentApi.previewTextById(props.attachment.id)
      textContent.value = resp.data
    }
  } catch {
    // 业务异常（413 / 404 / 500）由 axios 拦截器统一弹错；
    // 关闭弹窗避免遗留空状态
    visible.value = false
  } finally {
    loading.value = false
  }
}

function cleanup() {
  if (blobUrl.value) {
    URL.revokeObjectURL(blobUrl.value)
    blobUrl.value = ''
  }
  textContent.value = ''
}

function handleClose() {
  visible.value = false
}

async function handleDownload() {
  if (!props.attachment) return
  try {
    const resp = await attachmentApi.download(props.attachment.id)
    saveBlobResponse(resp, props.attachment.fileName || 'attachment')
  } catch {
    // 拦截器已弹错
  }
}

onBeforeUnmount(cleanup)
</script>

<style scoped>
.preview-wrap {
  min-height: 80vh;
  max-height: 86vh;
  background: var(--ha-surface);
  color: var(--ha-ink);
  border-radius: var(--ha-radius-md);
  overflow: auto;
  display: flex;
  flex-direction: column;
}
.preview-iframe {
  width: 100%;
  height: 80vh;
  border: none;
  background: var(--ha-surface);
}
.preview-md {
  flex: 1;
  padding: 20px 28px;
  background: var(--ha-surface);
  overflow: auto;
}
.preview-pre {
  flex: 1;
  margin: 0;
  padding: 20px 24px;
  font-family: var(--ha-font-mono);
  font-size: 13px;
  line-height: 1.6;
  color: var(--ha-ink);
  background: var(--ha-surface);
  white-space: pre-wrap;
  word-break: break-word;
  overflow: auto;
  tab-size: 2;
}
</style>