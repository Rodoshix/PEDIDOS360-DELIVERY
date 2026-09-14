export const PAGO_ERROR_MESSAGES = Object.freeze({
  NOT_CONFIGURED: 'El servicio de Pagos no está conectado.',
  LOAD_FAILED: 'No se pudo consultar el pago. Puedes reintentar la consulta.',
  CREATE_FAILED: 'No se pudo registrar el pago. Inténtalo de nuevo.',
  FORBIDDEN: 'No tienes permiso para esta operación de pago.',
  CONFLICT: 'El pedido ya tiene un pago activo o la clave se reutilizó.',
  NOT_FOUND: 'Ese pago o pedido no existe.',
  INVALID_RESPONSE: 'El servicio devolvió un pago inválido. No se aplicó esa respuesta.',
  INVALID_COMMAND: 'La operación de pago no es válida.',
  INVALID_REQUEST: 'Revisa los datos del pago antes de continuar.',
  UNAUTHORIZED: 'La API rechazó la sesión. Vuelve a iniciar sesión con Microsoft.',
  INTERACTION_REQUIRED: 'Microsoft necesita confirmar el acceso a la API.',
  RECONCILE: 'Consulta el pago para confirmar su estado antes de otra operación.',
})

export class PagoServiceError extends Error {
  constructor(code) {
    const safeCode = Object.hasOwn(PAGO_ERROR_MESSAGES, code) ? code : 'LOAD_FAILED'
    super(PAGO_ERROR_MESSAGES[safeCode])
    this.name = 'PagoServiceError'
    this.code = safeCode
  }
}
