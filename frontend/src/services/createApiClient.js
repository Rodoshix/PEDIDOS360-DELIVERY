import axios, { AxiosHeaders } from 'axios'
import { ApiAccessError } from '../auth/ApiAccessError.js'

function validPath(path) {
  try {
    return path.split('/').every(segment => {
      const decoded = decodeURIComponent(segment)
      return !decoded.includes('/') && !decoded.includes('\\') && !decoded.includes('%')
        && ![...decoded].some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127)
        && !(decoded !== segment && (decoded === '.' || decoded === '..'))
    })
  } catch { return false }
}

export function createApiClient({ baseURL, origin, getAccessToken, transport = axios.getAdapter('fetch') }) {
  let allowed
  try {
    allowed = new URL(baseURL, origin)
    const localHttp = allowed.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(allowed.hostname)
    if ((!localHttp && allowed.protocol !== 'https:') || allowed.username || allowed.password
      || allowed.search || allowed.hash || !validPath(allowed.pathname)) throw new Error()
  } catch { throw new ApiAccessError('API_CONFIG_INVALID') }
  const prefix = allowed.pathname.replace(/\/+$/, '')

  function resolveDestination(config) {
    try {
      if (/^[a-z][a-z\d+.-]*:/iu.test(config.url || '') && !/^https?:/iu.test(config.url)) throw new Error()
      const raw = axios.getUri(config)
      if (raw.includes('\\') || [...raw].some(char => char.charCodeAt(0) < 32)) throw new Error()
      const target = new URL(raw, origin)
      if (target.origin !== allowed.origin || target.username || target.password || target.hash
        || !validPath(target.pathname) || (prefix && target.pathname !== prefix && !target.pathname.startsWith(prefix + '/'))) throw new Error()
      return target.href
    } catch { throw new ApiAccessError('API_DESTINATION_BLOCKED') }
  }

  async function safeTransport(config) {
    // Verificar otra vez después de transformRequest; no permitir cambiar destino o transporte.
    config.url = resolveDestination(config)
    config.baseURL = undefined
    config.params = undefined
    config.adapter = safeTransport
    config.auth = undefined
    if (config.authRequired === false) config.headers.delete('Authorization')
    config.withCredentials = false
    config.withXSRFToken = false
    config.fetchOptions = { redirect: 'error', credentials: 'omit' }
    config.maxRedirects = undefined
    config.validateStatus = status => status >= 200 && status < 300
    return transport(config)
  }

  const client = axios.create({ baseURL: allowed.href.replace(/\/$/, ''), timeout: 15_000, headers: { Accept: 'application/json' } })
  client.interceptors.request.use(async config => {
    // Este cliente es exclusivo de nuestra API, incluso para peticiones públicas.
    resolveDestination(config)
    config.headers = AxiosHeaders.from(config.headers)
    config.headers.delete('Authorization')
    config.auth = undefined
    config.adapter = safeTransport
    if (config.signal?.aborted) throw new axios.CanceledError()
    if (config.authRequired !== false) {
      const token = await getAccessToken()
      if (config.signal?.aborted) throw new axios.CanceledError()
      if (typeof token !== 'string' || !token || /\s/u.test(token)) throw new ApiAccessError('TOKEN_UNAVAILABLE')
      config.headers.set('Authorization', `Bearer ${token}`)
    }
    return config
  })
  client.interceptors.response.use(
    response => ({ data: response.data, status: response.status, statusText: response.statusText, headers: response.headers }),
    error => {
      if (error instanceof ApiAccessError) return Promise.reject(error)
      if (axios.isCancel(error)) return Promise.reject(new axios.CanceledError('Petición cancelada.'))
      const status = error?.response?.status
      const code = status === 401 ? 'API_UNAUTHORIZED' : status === 403 ? 'API_FORBIDDEN'
        : status ? 'API_ERROR' : ['ETIMEDOUT', 'ECONNABORTED'].includes(error?.code) ? 'API_TIMEOUT' : 'API_NETWORK'
      return Promise.reject(new ApiAccessError(code, status))
    },
  )
  return client
}
