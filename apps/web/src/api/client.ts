import axios, { AxiosError } from 'axios'

const configuredBaseUrl = import.meta.env.VITE_API_BASE_URL
const sameOriginBaseUrl = import.meta.env.PROD ? '' : 'http://localhost:8080'
export const tokenKey = 'xiyiyun_web_token'

export const apiClient = axios.create({
  baseURL: configuredBaseUrl && configuredBaseUrl !== '/api' ? configuredBaseUrl : sameOriginBaseUrl,
  timeout: 20000
})

apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem(tokenKey)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export function getApiErrorMessage(error: unknown) {
  if (error instanceof AxiosError) {
    const data = error.response?.data as { message?: string; msg?: string; error?: string } | undefined
    if (data?.message) return data.message
    if (data?.msg) return data.msg
    if (data?.error) return data.error
    if (error.code === 'ERR_NETWORK') return '后端服务未启动或网络不可用。'
    if (error.code === 'ECONNABORTED') return '接口响应超时，请稍后重试。'
    if (error.response?.status) return `请求失败（${error.response.status}）`
  }
  if (error instanceof Error && error.message) return error.message
  return '请求失败，请稍后重试。'
}

export function isAmbiguousRequestError(error: unknown) {
  if (!(error instanceof AxiosError)) return false
  return !error.response || ['ECONNABORTED', 'ETIMEDOUT', 'ERR_NETWORK'].includes(error.code || '')
}

export function isNotFoundError(error: unknown) {
  if (error instanceof AxiosError) {
    if (error.response?.status === 404) return true
    if (error.response?.status) return false
  }
  const message = error instanceof Error ? error.message : String(error || '')
  return /not found|不存在|已下架|已删除/i.test(message)
}

export function isAuthenticationError(error: unknown) {
  if (error instanceof AxiosError) {
    return error.response?.status === 401 || error.response?.status === 403
  }
  const message = error instanceof Error ? error.message : String(error || '')
  return /unauthorized|forbidden|登录(?:已)?失效|请(?:先)?登录/i.test(message)
}
