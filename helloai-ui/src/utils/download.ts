import type { AxiosResponse } from 'axios'

/**
 * 从 Content-Disposition 解析下载文件名：RFC 5987 filename*（UTF-8 中文）优先，
 * 回退 filename，都取不到用 fallback。
 */
export function parseDispositionFilename(disposition: string | undefined, fallback: string): string {
  if (!disposition) return fallback
  const star = disposition.match(/filename\*=(?:UTF-8'')?([^;]+)/i)
  if (star && star[1]) {
    try {
      return decodeURIComponent(star[1].trim().replace(/^"|"$/g, ''))
    } catch { /* 编码异常回退 filename */ }
  }
  const plain = disposition.match(/filename="?([^";]+)"?/i)
  if (plain && plain[1]) return plain[1]
  return fallback
}

/**
 * 把 axios blob 响应保存为浏览器下载（token 在请求头，不能用裸 <a href> 直链，
 * 须经 request 实例带认证拉取后本地落盘）。
 */
export function saveBlobResponse(response: AxiosResponse<Blob>, fallbackName: string) {
  const fileName = parseDispositionFilename(response.headers['content-disposition'], fallbackName)
  const url = URL.createObjectURL(response.data)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  a.click()
  URL.revokeObjectURL(url)
}
