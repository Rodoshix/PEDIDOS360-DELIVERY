import { authErrorMessage } from './authErrors.js'

export function selectSessionAccount(accounts, activeAccount, tenantId) {
  const eligible = accounts.filter(account => account.tenantId?.toLowerCase() === tenantId.toLowerCase())
  const active = eligible.find(account => account.homeAccountId === activeAccount?.homeAccountId
    && account.localAccountId === activeAccount?.localAccountId)
  // Con varias cuentas no se elige arbitrariamente la primera.
  return active || (eligible.length === 1 ? eligible[0] : null)
}

export async function restoreSession(instance, tenantId, returnDestinationStore) {
  try {
    const result = await instance.handleRedirectPromise()
    if (result?.account) {
      if (result.account.tenantId?.toLowerCase() !== tenantId.toLowerCase()) {
        instance.setActiveAccount(null)
        returnDestinationStore?.clear()
        return { error: authErrorMessage({ errorCode: 'tenant_mismatch' }) }
      }
      instance.setActiveAccount(result.account)
      return { error: null, returnTo: returnDestinationStore?.consume(result.state) ?? '/' }
    } else {
      returnDestinationStore?.clear()
      instance.setActiveAccount(selectSessionAccount(instance.getAllAccounts(), instance.getActiveAccount(), tenantId))
    }
    return { error: null }
  } catch (error) {
    returnDestinationStore?.clear()
    // Nunca propagar el mensaje crudo de Entra: puede incluir datos de cuenta o de la respuesta.
    return { error: authErrorMessage(error) }
  }
}

export function createSessionActions(instance, { onPending, onError, returnDestinationStore }) {
  let pending = false

  async function run(operation, action) {
    if (pending) return
    pending = true
    onError(null)
    onPending(operation)
    try {
      await action()
    } catch (error) {
      returnDestinationStore?.clear()
      onError(authErrorMessage(error, operation))
    } finally {
      pending = false
      onPending(null)
    }
  }

  return {
    login: destination => run('login', () => instance.loginRedirect({
      scopes: ['openid', 'profile'],
      prompt: 'select_account',
      state: returnDestinationStore?.save(destination),
    })),
    logout: account => account
      ? run('logout', () => {
        returnDestinationStore?.clear()
        return instance.logoutRedirect({ account })
      })
      : Promise.resolve(),
  }
}
