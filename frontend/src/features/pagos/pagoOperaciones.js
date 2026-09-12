import { PagoServiceError } from './pagoErrors.js'

export const METODOS_PAGO = Object.freeze(['TARJETA', 'EFECTIVO'])

export const ETIQUETAS_METODO = Object.freeze({
  TARJETA: 'Tarjeta',
  EFECTIVO: 'Efectivo',
})

export const ETIQUETAS_ESTADO_PAGO = Object.freeze({
  PENDIENTE: 'Pendiente de cobro',
  APROBADO: 'Aprobado',
  RECHAZADO: 'Rechazado',
})

const positiveId = value => Number.isSafeInteger(value) && value > 0

export function esMetodoValido(metodo) {
  return METODOS_PAGO.includes(metodo)
}

export function validatePagoDraft(draft) {
  const errores = {}
  if (!draft) return { pedidoId: 'Falta el pedido.' }
  if (!positiveId(draft.pedidoId)) errores.pedidoId = 'El pedido no es válido.'
  if (!esMetodoValido(draft.metodo)) errores.metodo = 'Elige un método de pago.'
  return errores
}

export function normalizePagoDraft(draft) {
  return Object.freeze({ pedidoId: draft.pedidoId, metodo: draft.metodo })
}

// Proyección defensiva del pago.
export function validatePagoResponse(value) {
  if (!value || !positiveId(value.pagoId) || !positiveId(value.pedidoId) || !positiveId(value.usuarioId)
    || !Number.isSafeInteger(value.monto) || value.monto < 0 || value.moneda !== 'CLP'
    || !esMetodoValido(value.metodo) || !Object.hasOwn(ETIQUETAS_ESTADO_PAGO, value.estado)
    || typeof value.fecha !== 'string' || !Number.isFinite(Date.parse(value.fecha))) {
    throw new PagoServiceError('INVALID_RESPONSE')
  }
  return Object.freeze({
    pagoId: value.pagoId, pedidoId: value.pedidoId, usuarioId: value.usuarioId,
    monto: value.monto, moneda: 'CLP', metodo: value.metodo, estado: value.estado, fecha: value.fecha,
  })
}
