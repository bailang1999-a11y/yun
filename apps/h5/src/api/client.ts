import axios, { AxiosError } from 'axios'

const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL
const sameOriginBaseUrl = import.meta.env.PROD ? '' : 'http://localhost:8080'

export const apiClient = axios.create({
  baseURL: configuredBaseUrl && configuredBaseUrl !== '/api' ? configuredBaseUrl : sameOriginBaseUrl,
  timeout: 8000
})

apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('xiyiyun_h5_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

export function getApiErrorMessage(error: unknown) {
  if (error instanceof AxiosError) {
    const data = error.response?.data as { message?: string; msg?: string; error?: string } | undefined
    if (data?.message) return data.message
    if (data?.msg) return data.msg
    if (data?.error) return data.error
    if (error.code === 'ERR_NETWORK') return '后端服务未启动或网络不可用，请稍后重试。'
    if (error.code === 'ECONNABORTED') return '接口响应超时，请稍后重试。'
    if (error.response?.status) return `请求失败（${error.response.status}）`
  }
  return '请求失败，请稍后重试。'
}
