const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const apiScopePattern = /^api:\/\/([0-9a-f-]+)\/access_as_user$/i

export class AuthConfigurationError extends Error {
  constructor(message) {
    super(message)
    this.name = 'AuthConfigurationError'
  }
}

function required(values, key) {
  const value = values[key]?.trim()
  if (!value) {
    throw new AuthConfigurationError(`Falta configurar ${key}.`)
  }
  return value
}

export function createAuthConfiguration(values, { origin } = {}) {
  const clientId = required(values, 'VITE_ENTRA_CLIENT_ID')
  const tenantId = required(values, 'VITE_ENTRA_TENANT_ID')
  const redirectUri = required(values, 'VITE_ENTRA_REDIRECT_URI')
  const apiScope = required(values, 'VITE_ENTRA_API_SCOPE')

  for (const [key, value] of Object.entries({ VITE_ENTRA_CLIENT_ID: clientId, VITE_ENTRA_TENANT_ID: tenantId })) {
    if (!uuidPattern.test(value)) {
      throw new AuthConfigurationError(`${key} debe ser un UUID válido de Microsoft Entra ID.`)
    }
  }

  let redirect
  try {
    redirect = new URL(redirectUri)
  } catch {
    throw new AuthConfigurationError('VITE_ENTRA_REDIRECT_URI debe ser una URL absoluta.')
  }
  const localHttp = redirect.protocol === 'http:' && redirect.hostname === 'localhost'
  if (redirect.protocol !== 'https:' && !localHttp) {
    throw new AuthConfigurationError('VITE_ENTRA_REDIRECT_URI requiere HTTPS; HTTP solo se admite en localhost para pruebas locales.')
  }
  if (redirect.username || redirect.password || redirect.search || redirect.hash) {
    throw new AuthConfigurationError('VITE_ENTRA_REDIRECT_URI no debe incluir credenciales, consulta ni fragmento.')
  }
  if (origin && redirect.origin !== origin) {
    throw new AuthConfigurationError('Abre el frontend en el mismo origen que VITE_ENTRA_REDIRECT_URI y comprueba el puerto.')
  }

  const scopeMatch = apiScope.match(apiScopePattern)
  if (!scopeMatch || !uuidPattern.test(scopeMatch[1]) || !apiScope.endsWith('/access_as_user')) {
    throw new AuthConfigurationError('VITE_ENTRA_API_SCOPE debe tener el formato api://<client-id-de-la-api>/access_as_user.')
  }
  if (scopeMatch[1].toLowerCase() === clientId.toLowerCase()) {
    throw new AuthConfigurationError('VITE_ENTRA_API_SCOPE debe identificar la API, no el registro del frontend.')
  }

  return {
    msalConfig: {
      auth: {
        clientId,
        authority: `https://login.microsoftonline.com/${tenantId}`,
        redirectUri,
        postLogoutRedirectUri: redirectUri,
        navigateToLoginRequestUrl: false,
      },
      cache: { cacheLocation: 'sessionStorage' },
      system: { loggerOptions: { piiLoggingEnabled: false } },
    },
    apiTokenRequest: { scopes: [apiScope] },
  }
}
