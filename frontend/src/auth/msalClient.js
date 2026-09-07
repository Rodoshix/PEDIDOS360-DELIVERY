import { PublicClientApplication } from '@azure/msal-browser'
import { createAuthConfiguration } from './authConfiguration.js'
import { restoreSession } from './session.js'
import { createReturnDestinationStore, restoreReturnDestination } from './returnDestination.js'

// Una única inicialización compartida, fuera del ciclo de renderizado de React.
let initialization

export function initializeMsal() {
  initialization ??= (async () => {
    const configuration = createAuthConfiguration(import.meta.env, {
      origin: window.location.origin,
    })
    const instance = new PublicClientApplication(configuration.msalConfig)
    await instance.initialize()
    const tenantId = import.meta.env.VITE_ENTRA_TENANT_ID.trim()
    const returnDestinationStore = createReturnDestinationStore(window.sessionStorage)
    const { error, returnTo } = await restoreSession(instance, tenantId, returnDestinationStore)
    restoreReturnDestination(window.history, returnTo)
    return { instance, tenantId, returnDestinationStore, initialError: error, apiTokenRequest: configuration.apiTokenRequest }
  })()
  return initialization
}
