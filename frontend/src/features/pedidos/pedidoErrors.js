export const PEDIDO_ERROR_MESSAGES = Object.freeze({
  NOT_CONFIGURED: 'El servicio de Pedidos no está conectado.',
  LOAD_FAILED: 'No se pudieron consultar tus pedidos. Puedes reintentar la consulta.',
  CREATE_FAILED: 'No se pudo crear el pedido. Revisa los datos e inténtalo de nuevo.',
  TRANSITION_FAILED: 'No se pudo cambiar el estado del pedido. Puedes reintentar.',
  FORBIDDEN: 'No tienes permiso para esta operación sobre pedidos.',
  CONFLICT: 'El pedido cambió de estado. Vuelve a consultarlo antes de reintentar.',
  NOT_FOUND: 'Ese pedido no existe.',
  INVALID_RESPONSE: 'El servicio devolvió un pedido inválido. No se aplicó esa respuesta.',
  INVALID_COMMAND: 'La operación del pedido no es válida.',
  INVALID_REQUEST: 'Revisa los datos del pedido antes de continuar.',
  UNAUTHORIZED: 'La API rechazó la sesión. Vuelve a iniciar sesión con Microsoft.',
  INTERACTION_REQUIRED: 'Microsoft necesita confirmar el acceso a la API.',
  RECONCILE: 'Consulta los pedidos para confirmar su estado antes de otra operación.',
})

export class PedidoServiceError extends Error {
  constructor(code) {
    const safeCode = Object.hasOwn(PEDIDO_ERROR_MESSAGES, code) ? code : 'LOAD_FAILED'
    super(PEDIDO_ERROR_MESSAGES[safeCode])
    this.name = 'PedidoServiceError'
    this.code = safeCode
  }
}
