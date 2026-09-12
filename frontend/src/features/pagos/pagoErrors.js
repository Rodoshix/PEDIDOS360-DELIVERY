export const PAGO_ERROR_MESSAGES = Object.freeze({
  NOT_CONFIGURED: 'El servicio de Pagos no está conectado.',
  LOAD_FAILED: 'No se pudo consultar el pago de prueba. Puedes reintentar la consulta.',
  CREATE_FAILED: 'No se pudo registrar el pago de prueba. Inténtalo de nuevo.',
  FORBIDDEN: 'Este escenario no permite acceder ni registrar pagos de prueba.',
  CONFLICT: 'Conflicto simulado: el pedido ya tiene un pago activo o la clave se reutilizó.',
  NOT_FOUND: 'Ese pago o pedido de prueba no existe.',
  INVALID_RESPONSE: 'El servicio devolvió un pago inválido. No se aplicó esa respuesta.',
  INVALID_COMMAND: 'La operación de pago no es válida.',
  INVALID_REQUEST: 'Revisa los datos del pago antes de continuar.',
})

export class PagoServiceError extends Error {
  constructor(code) {
    const safeCode = Object.hasOwn(PAGO_ERROR_MESSAGES, code) ? code : 'LOAD_FAILED'
    super(PAGO_ERROR_MESSAGES[safeCode])
    this.name = 'PagoServiceError'
    this.code = safeCode
  }
}
