export const PEDIDO_ERROR_MESSAGES = Object.freeze({
  NOT_CONFIGURED: 'El servicio de Pedidos no está conectado.',
  LOAD_FAILED: 'No se pudo consultar los pedidos de prueba. Puedes reintentar la consulta.',
  CREATE_FAILED: 'No se pudo crear el pedido de prueba. Revisa los datos e inténtalo de nuevo.',
  TRANSITION_FAILED: 'No se pudo cambiar el estado del pedido de prueba. Puedes reintentar.',
  FORBIDDEN: 'Este escenario no permite acceder ni modificar los pedidos de prueba.',
  CONFLICT: 'Conflicto simulado: el pedido cambió de estado. Vuelve a consultarlo antes de reintentar.',
  NOT_FOUND: 'Ese pedido de prueba no existe.',
  INVALID_RESPONSE: 'El servicio devolvió un pedido inválido. No se aplicó esa respuesta.',
  INVALID_COMMAND: 'La operación del pedido no es válida.',
  INVALID_REQUEST: 'Revisa los datos del pedido antes de continuar.',
})

export class PedidoServiceError extends Error {
  constructor(code) {
    const safeCode = Object.hasOwn(PEDIDO_ERROR_MESSAGES, code) ? code : 'LOAD_FAILED'
    super(PEDIDO_ERROR_MESSAGES[safeCode])
    this.name = 'PedidoServiceError'
    this.code = safeCode
  }
}
