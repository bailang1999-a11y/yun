import axios from 'axios'

export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_ADMIN_API_BASE_URL || 'http://localhost:8080',
  timeout: 15000
})

apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem('xiyiyun_admin_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})
