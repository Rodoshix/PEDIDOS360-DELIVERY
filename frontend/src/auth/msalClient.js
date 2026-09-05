import { PublicClientApplication } from '@azure/msal-browser'
import { createAuthConfiguration } from './authConfiguration.js'

// Una única inicialización compartida, fuera del ciclo de renderizado de React.
let initialization

export function initializeMsal() {
  initialization ??= (async () => {
    const configuration = createAuthConfiguration(import.meta.env, {
      origin: window.location.origin,
    })
    const instance = new PublicClientApplication(configuration.msalConfig)
    await instance.initialize()
    return { instance, apiTokenRequest: configuration.apiTokenRequest }
  })()
  return initialization
}
