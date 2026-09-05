import { authErrorMessage } from './authErrors.js'

export function selectSessionAccount(accounts, activeAccount, tenantId) {
  const eligible = accounts.filter(account => account.tenantId?.toLowerCase() === tenantId.toLowerCase())
  const active = eligible.find(account => account.homeAccountId === activeAccount?.homeAccountId
    && account.localAccountId === activeAccount?.localAccountId)
  // Con varias cuentas no se elige arbitrariamente la primera.
  return active || (eligible.length === 1 ? eligible[0] : null)
}

export async function restoreSession(instance, tenantId) {
  try {
    const result = await instance.handleRedirectPromise()
    if (result?.account) {
      if (result.account.tenantId?.toLowerCase() !== tenantId.toLowerCase()) {
        instance.setActiveAccount(null)
        return { error: authErrorMessage({ errorCode: 'tenant_mismatch' }) }
      }
      instance.setActiveAccount(result.account)
    } else {
      instance.setActiveAccount(selectSessionAccount(instance.getAllAccounts(), instance.getActiveAccount(), tenantId))
    }
    return { error: null }
  } catch (error) {
    // Nunca propagar el mensaje crudo de Entra: puede incluir datos de cuenta o de la respuesta.
    return { error: authErrorMessage(error) }
  }
}

export function createSessionActions(instance, { onPending, onError }) {
  let pending = false

  async function run(operation, action) {
    if (pending) return
    pending = true
    onError(null)
    onPending(operation)
    try {
      await action()
    } catch (error) {
      onError(authErrorMessage(error, operation))
    } finally {
      pending = false
      onPending(null)
    }
  }

  return {
    login: () => run('login', () => instance.loginRedirect({
      scopes: ['openid', 'profile'],
      prompt: 'select_account',
    })),
    logout: account => account
      ? run('logout', () => instance.logoutRedirect({ account }))
      : Promise.resolve(),
  }
}
