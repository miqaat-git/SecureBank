import axios from 'axios'
import keycloak from './keycloak'

const api = axios.create({
  baseURL: '',
  timeout: 15000,
})

api.interceptors.request.use(async (config) => {
  if (keycloak.authenticated && keycloak.token) {
    try {
      await keycloak.updateToken(30)
    } catch {
      // Token refresh failed — proceed, server will 401
    }
    config.headers = config.headers ?? {}
    config.headers.Authorization = `Bearer ${keycloak.token}`
  }
  return config
})

api.interceptors.response.use(
  (r) => r,
  (err) => {
    if (err.response?.status === 401 && keycloak.authenticated) {
      keycloak.login({ redirectUri: window.location.href })
    }
    return Promise.reject(err)
  }
)

export default api