import axios from 'axios'

const configuredBaseUrl = import.meta.env.VITE_ADMIN_API_BASE_URL
const sameOriginBaseUrl = import.meta.env.PROD ? '' : 'http://localhost:8080'

export const apiClient = axios.create({
  baseURL: configuredBaseUrl && configuredBaseUrl !== '/api' ? configuredBaseUrl : sameOriginBaseUrl,
  timeout: 15000
})

apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('xiyiyun_admin_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})
