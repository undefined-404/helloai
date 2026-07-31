import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({ html: false, breaks: true })

/**
 * 去除 Markdown 格式字符，返回纯文本。
 * 用于在纯文本场景（描述弹窗、tooltip 等）中避免 ## ** -- 等标记露出。
 * 原理：先将 Markdown 渲染为 HTML，再剥离所有 HTML 标签，保留纯文本内容。
 */
export function stripMarkdown(text: string): string {
  if (!text) return ''
  const html = md.render(text)
  return html.replace(/<[^>]*>/g, '').trim()
}
