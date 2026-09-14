import { ApiAccessError } from '../../auth/ApiAccessError.js'
import { validatePagoResponse } from './pagoOperaciones.js'
import { PagoServiceError } from './pagoErrors.js'

/** Traduce un fallo del transporte o del dominio a un error seguro de Pagos. */
export function pagoFailure(error, fallback = 'LOAD_FAILED') {
  if (error instanceof PagoServiceError) return new PagoServiceError(error.code)
  if (error instanceof ApiAccessError) {
    if (error.code === 'INTERACTION_REQUIRED') return new PagoServiceError('INTERACTION_REQUIRED')
    if (error.status === 401 || ['SESSION_REQUIRED', 'SESSION_CHANGED'].includes(error.code)) return new PagoServiceError('UNAUTHORIZED')
    if (error.status === 403) return new PagoServiceError('FORBIDDEN')
    if (error.status === 404) return new PagoServiceError('NOT_FOUND')
    if (error.status === 409) return new PagoServiceError('CONFLICT')
    if (error.status === 400) return new PagoServiceError('INVALID_REQUEST')
  }
  return new PagoServiceError(fallback)
}

const positive = id => Number.isSafeInteger(id) && id > 0
// La clave viaja como cabecera: 1-80 caracteres [A-Za-z0-9_-] (contrato #47).
const CLAVE_VALIDA = /^[A-Za-z0-9_-]{1,80}$/

function validarClave(clave) {
  if (typeof clave !== 'string' || !CLAVE_VALIDA.test(clave)) throw new PagoServiceError('INVALID_COMMAND')
  return clave
}

function pago(response, esperado = 200) {
  if (!response || response.status !== esperado) throw new PagoServiceError('INVALID_RESPONSE')
  return validatePagoResponse(response.data)
}

/**
 * Adapter HTTP de Pagos contra el BFF (Bearer delegado del usuario).
 * Rutas del contrato #47: POST /pagos (Idempotency-Key obligatoria),
 * GET /pagos/{id}, GET /pagos/pedido/{pedidoId}.
 */
export function createPagoHttpAdapter(client) {
  return {
    async get(pedidoId, { signal } = {}) {
      if (!positive(pedidoId)) throw new PagoServiceError('INVALID_COMMAND')
      try {
        signal?.throwIfAborted()
        const response = await client.get('/pagos/pedido/' + pedidoId, { signal })
        signal?.throwIfAborted()
        if (response.status !== 200 || !Array.isArray(response.data)) throw new PagoServiceError('INVALID_RESPONSE')
        const pagos = response.data.map(value => validatePagoResponse(value))
        if (pagos.length === 0) return null
        // El más reciente del pedido.
        return pagos.reduce((ultimo, actual) => actual.fecha > ultimo.fecha ? actual : ultimo)
      } catch (error) { throw pagoFailure(error) }
    },

    async create(draft, clave, { signal } = {}) {
      if (!draft || !positive(draft.pedidoId)) throw new PagoServiceError('INVALID_COMMAND')
      validarClave(clave)
      try {
        signal?.throwIfAborted()
        const response = await client.post('/pagos',
          { pedidoId: draft.pedidoId, metodo: draft.metodo },
          { signal, headers: { 'Idempotency-Key': clave } })
        signal?.throwIfAborted()
        return pago(response, 201)
      } catch (error) { throw pagoFailure(error, 'CREATE_FAILED') }
    },
  }
}
