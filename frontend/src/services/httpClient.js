import { environment } from '../config/environment.js'
import { createApiClient } from './createApiClient.js'
import { ApiAccessError } from '../auth/ApiAccessError.js'

let tokenProvider

export function configureApiAuthentication(provider) {
  tokenProvider = provider
}

const httpClient = createApiClient({
  baseURL: environment.apiBaseUrl,
  origin: window.location.origin,
  getAccessToken: () => {
    if (!tokenProvider) throw new ApiAccessError('AUTH_NOT_READY')
    return tokenProvider.getAccessToken()
  },
})

export default httpClient
