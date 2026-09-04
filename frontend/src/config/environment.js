const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL?.trim()

export const environment = Object.freeze({
  apiBaseUrl: configuredApiBaseUrl?.replace(/\/+$/, '') || '/api',
})
