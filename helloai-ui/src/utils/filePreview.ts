// ============================================================
// 附件预览可行性工具（前端估算）
// ============================================================
//
// 与后端 AttachmentService.isPreviewable 规则保持镜像同步：
// - 命中可预览后缀白名单
// - 文件大小未超 5 MiB
//
// 前端据此在按钮上做禁用 / 启用，避免点击后端才发现不可预览。
// 后端是权威源 — 前端判断为可预览但后端拒绝（413）时，
// 走 axios 拦截器弹错并提示走下载。

/**
 * 可预览扩展名白名单（不含点号）。
 * 与后端 AttachmentServiceImpl.PREVIEWABLE_MIME_PREFIXES 镜像同步。
 * 后端规则新增时这里同步追加。
 */
export const PREVIEWABLE_EXTENSIONS: ReadonlySet<string> = new Set([
  // 文本类
  'txt', 'log', 'md', 'json', 'xml', 'yaml', 'yml', 'csv', 'html', 'htm',
  // 源码类（JS / TS 家族）
  'js', 'mjs', 'cjs', 'jsx', 'ts', 'tsx',
  // 图片
  'png', 'jpg', 'jpeg', 'gif', 'svg',
  // 文档
  'pdf'
])

/**
 * 浏览器内联预览文件大小上限（5 MiB）。
 * 与后端 AttachmentServiceImpl.PREVIEW_MAX_SIZE_BYTES 镜像同步。
 */
export const PREVIEW_MAX_SIZE_BYTES = 5 * 1024 * 1024

export interface PreviewableInput {
  fileName?: string | null
  fileSize?: number | null
}

/**
 * 前端估算附件是否可以浏览器内联预览。
 * 判定规则：后缀命中白名单 + 文件大小未超 5 MiB。
 */
export function isPreviewableAttachment(att: PreviewableInput | null | undefined): boolean {
  if (!att || !att.fileName) return false
  const dot = att.fileName.lastIndexOf('.')
  if (dot < 0 || dot === att.fileName.length - 1) return false
  const ext = att.fileName.slice(dot + 1).toLowerCase()
  if (!PREVIEWABLE_EXTENSIONS.has(ext)) return false
  if (att.fileSize != null && att.fileSize > PREVIEW_MAX_SIZE_BYTES) return false
  return true
}
