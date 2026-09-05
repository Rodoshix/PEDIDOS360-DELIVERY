import { PublicClientApplication } from '@azure/msal-browser'
import { createAuthConfiguration } from './authConfiguration.js'
import { restoreSession } from './session.js'

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
    const { error } = await restoreSession(instance, tenantId)
    return { instance, tenantId, initialError: error, apiTokenRequest: configuration.apiTokenRequest }
  })()
  return initialization
}
