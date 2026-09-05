export function authErrorMessage(error, operation = 'login') {
  switch (error?.errorCode) {
    case 'user_cancelled':
    case 'access_denied':
      return 'Se canceló el acceso o no se concedió el permiso. Puedes intentarlo nuevamente.'
    case 'interaction_in_progress':
      return 'Ya hay una operación de acceso en curso. Termínala antes de volver a intentarlo.'
    case 'no_network_connectivity':
    case 'post_request_failed':
    case 'get_request_failed':
      return 'No pudimos conectar con Microsoft. Revisa tu conexión e inténtalo nuevamente.'
    case 'tenant_mismatch':
      return 'La cuenta no pertenece al directorio configurado para Pedidos360.'
    default:
      return operation === 'logout'
        ? 'No se pudo completar el cierre de sesión en Microsoft. Vuelve a intentarlo; cerrar esta pestaña no garantiza cerrar la sesión de Microsoft.'
        : 'No se pudo iniciar sesión. Comprueba la cuenta, el consentimiento y la configuración de Entra ID.'
  }
}
