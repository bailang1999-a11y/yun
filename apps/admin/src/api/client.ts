import axios from 'axios'

const configuredBaseUrl = import.meta.env.VITE_ADMIN_API_BASE_URL
const sameOriginBaseUrl = import.meta.env.PROD ? '' : 'http://localhost:8080'

export const apiClient = axios.create({
  baseURL: configuredBaseUrl && configuredBaseUrl !== '/api' ? configuredBaseUrl : sameOriginBaseUrl,
  timeout: 15000
})

interface ApiErrorEnvelope {
  message?: unknown
  msg?: unknown
  error?: unknown
}

apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('xiyiyun_admin_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error)) {
      error.message = getAdminApiErrorMessage(error)
    }

    return Promise.reject(error)
  }
)

export function getAdminApiErrorMessage(error: unknown) {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as ApiErrorEnvelope | undefined
    const message = apiErrorMessage(data)
    if (message) return message
    if (error.code === 'ERR_NETWORK') return '后端服务未启动或网络不可用，请稍后重试。'
    if (error.code === 'ECONNABORTED') return '接口响应超时，请稍后重试。'
    if (error.response?.status === 401) return '登录已失效，请重新登录。'
    if (error.response?.status) return `请求失败（${error.response.status}）`
  }

  if (error instanceof Error && error.message) return error.message
  return '请求失败，请稍后重试。'
}

function apiErrorMessage(data?: ApiErrorEnvelope, fallback = '') {
  if (!data || typeof data !== 'object') return fallback
  const message = data.message || data.msg || data.error
  return typeof message === 'string' && message.trim() ? message : fallback
}
