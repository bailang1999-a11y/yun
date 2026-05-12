import { apiClient } from './client'
import { numberValue, text } from './normalize'
import { type ApiEnvelope, unwrapValue } from './response'

export interface UploadResult {
  url: string
  filename: string
  size: number
  contentType: string
}

export async function uploadImage(file: File): Promise<UploadResult> {
  const formData = new FormData()
  formData.append('file', file)

  const { data } = await apiClient.post<unknown>('/api/admin/uploads/images', formData)
  const item = unwrapValue<Record<string, unknown>>(data as ApiEnvelope<Record<string, unknown>>)

  return {
    url: text(item.url),
    filename: text(item.filename),
    size: numberValue(item.size),
    contentType: text(item.contentType)
  }
}
