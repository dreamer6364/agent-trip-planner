import instance, { toApiError } from '@/api/index'

const EXT_CONTENT_TYPE: Record<string, string> = {
  json: 'application/json;charset=utf-8',
  ics: 'text/calendar;charset=utf-8',
  pdf: 'application/pdf',
  png: 'image/png',
}

function contentTypeOf(filename: string, fallback?: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() ?? ''
  return EXT_CONTENT_TYPE[ext] || fallback || 'application/octet-stream'
}

function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  // Safari 需要延后释放，否则下载可能被中断
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/**
 * 下载导出文件
 *
 * 同源地址：走带鉴权的 axios 请求拿二进制，用 <a download> 触发浏览器保存，
 * 绕开 window.open 弹窗拦截与 data: URL 顶层导航限制；data: 地址走 fetch 兜底。
 *
 * @param downloadUrl 后端返回的下载地址（/api/... 或 data:）
 * @param filename 保存的文件名（从 Content-Disposition 或导出元数据取得）
 */
export async function downloadFile(downloadUrl: string, filename: string): Promise<void> {
  try {
    if (downloadUrl.startsWith('data:')) {
      const blob = await fetch(downloadUrl).then((r) => r.blob())
      saveBlob(blob, filename)
      return
    }

    const response = await instance.get<ArrayBuffer>(downloadUrl, {
      responseType: 'arraybuffer',
      headers: { Accept: '*/*' },
    })
    const headers = response.headers as Record<string, string | undefined>
    const headerType = headers?.['content-type'] || ''
    const blob = new Blob([response.data], { type: contentTypeOf(filename, headerType) })
    saveBlob(blob, filename)
  } catch (error) {
    throw toApiError(error)
  }
}
