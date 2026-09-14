const messages = {
  AUTH_NOT_READY: 'El acceso a la API todavía no está preparado.',
  SESSION_REQUIRED: 'Inicia sesión antes de acceder a esta información.',
  AUTH_BUSY: 'Hay una operación de acceso en curso. Espera y vuelve a intentarlo.',
  SESSION_CHANGED: 'La sesión cambió durante la petición. Vuelve a intentarlo.',
  INTERACTION_REQUIRED: 'Microsoft necesita confirmar el acceso a la API. Pulsa Continuar con Microsoft.',
  TOKEN_UNAVAILABLE: 'No se pudo obtener el acceso a la API. Comprueba tu conexión y vuelve a intentarlo.',
  API_DESTINATION_BLOCKED: 'Se bloqueó una petición fuera del destino permitido para la API.',
  API_CONFIG_INVALID: 'La dirección configurada para la API no es válida.',
  API_UNAUTHORIZED: 'La API rechazó la credencial (401). Comprueba el token y la configuración del backend.',
  API_FORBIDDEN: 'La API no permite esta operación (403). Comprueba los permisos de la cuenta.',
  API_NETWORK: 'No pudimos conectar con la API. Revisa su disponibilidad, CORS y posibles redirecciones.',
  API_TIMEOUT: 'La API tardó demasiado en responder. Puedes volver a intentarlo.',
  API_ERROR: 'La API no pudo completar la operación.',
}

// No adjuntar el error original: Axios/MSAL pueden incluir credenciales o datos personales.
export class ApiAccessError extends Error {
  constructor(code, status) {
    super(messages[code] || messages.API_ERROR)
    this.name = 'ApiAccessError'
    this.code = code
    if (status !== undefined) this.status = status
  }
}
