import { CacheLookupPolicy, EventMessageUtils, EventType, InteractionRequiredAuthError, InteractionStatus } from '@azure/msal-browser'
import { ApiAccessError } from './ApiAccessError.js'

function accountKey(account) {
  return account ? `${account.tenantId?.toLowerCase()}:${account.homeAccountId}:${account.localAccountId}` : null
}

export function createApiTokenProvider(instance, { tenantId, scopes }) {
  let interaction = InteractionStatus.None
  let generation = 0
  let pending
  let pendingKey
  const callbackId = instance.addEventCallback(event => {
    interaction = EventMessageUtils.getInteractionStatusFromEvent(event, interaction) ?? interaction
    if (event.eventType === EventType.LOGOUT_START || event.eventType === EventType.ACTIVE_ACCOUNT_CHANGED) generation += 1
  })

  function currentAccount() {
    if (interaction !== InteractionStatus.None) throw new ApiAccessError('AUTH_BUSY')
    const account = instance.getActiveAccount()
    if (!account || account.tenantId?.toLowerCase() !== tenantId.toLowerCase()) throw new ApiAccessError('SESSION_REQUIRED')
    return account
  }

  async function acquire(account, version) {
    try {
      const result = await instance.acquireTokenSilent({
        account, scopes: [...scopes],
        // Caché y refresh token, sin iframe ni ventanas de acceso inesperadas.
        cacheLookupPolicy: CacheLookupPolicy.AccessTokenAndRefreshToken,
      })
      if (generation !== version || accountKey(currentAccount()) !== accountKey(account)) throw new ApiAccessError('SESSION_CHANGED')
      if (!result.accessToken || result.accessToken === result.idToken || result.tokenType?.toLowerCase() !== 'bearer'
        || accountKey(result.account) !== accountKey(account) || !(result.expiresOn?.getTime() > Date.now())
        || !scopes.every(scope => result.scopes?.includes(scope) || result.scopes?.includes(scope.slice(scope.lastIndexOf('/') + 1)))) {
        throw new ApiAccessError('TOKEN_UNAVAILABLE')
      }
      return result.accessToken
    } catch (error) {
      if (error instanceof ApiAccessError) throw error
      const requiresInteraction = error instanceof InteractionRequiredAuthError
        || ['interaction_required', 'consent_required', 'login_required', 'no_tokens_found', 'refresh_token_expired', 'token_refresh_required'].includes(error?.errorCode)
      throw new ApiAccessError(requiresInteraction ? 'INTERACTION_REQUIRED' : 'TOKEN_UNAVAILABLE')
    }
  }

  return {
    async getAccessToken() {
      const account = currentAccount()
      const key = `${generation}:${accountKey(account)}`
      if (pending && pendingKey === key) return pending
      const operation = acquire(account, generation)
      pending = operation
      pendingKey = key
      try { return await operation } finally { if (pending === operation) pending = undefined }
    },
    dispose() { instance.removeEventCallback(callbackId) },
  }
}
