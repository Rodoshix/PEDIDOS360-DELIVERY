import { normalizePagoDraft, validatePagoDraft, validatePagoResponse } from './pagoOperaciones.js'
import { PagoServiceError } from './pagoErrors.js'

// Controller por pantalla/cuenta. No conoce MSAL, HTTP ni almacenamiento.
export function createPagoController() {
  let state = Object.freeze({ status: 'idle', pago: null, error: null, operation: null, idempotencyKey: null })
  let adapter = null
  let request = null
  let generation = 0
  const listeners = new Set()
  const publish = next => { state = Object.freeze(next); listeners.forEach(listener => listener()) }

  function cancelPending() {
    generation++
    request?.abort()
    request = null
  }

  async function run(operation, payload) {
    if (request) return false
    const currentGeneration = ++generation
    const abort = new AbortController()
    request = abort
    const currentAdapter = adapter
    const previous = state.pago
    publish({ ...state, status: operation === 'load' ? 'loading' : 'saving', error: null, operation })
    try {
      abort.signal.throwIfAborted()
      if (!currentAdapter) throw new PagoServiceError('NOT_CONFIGURED')
      let result
      if (operation === 'load') {
        result = await currentAdapter.get(payload, { signal: abort.signal })
      } else if (operation === 'write') {
        if (Object.keys(validatePagoDraft(payload.draft)).length) throw new PagoServiceError('INVALID_REQUEST')
        // La clave de idempotencia se mantiene por intento: reintentar no duplica el pago.
        const key = state.idempotencyKey ?? `web-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
        publish({ ...state, idempotencyKey: key })
        result = await currentAdapter.create(normalizePagoDraft(payload.draft), key, { signal: abort.signal })
      } else {
        throw new PagoServiceError('INVALID_COMMAND')
      }
      if (abort.signal.aborted || generation !== currentGeneration) return false
      publish({ status: 'ready', pago: validatePagoResponse(result), error: null, operation: null, idempotencyKey: null })
      return true
    } catch (failure) {
      if (abort.signal.aborted || generation !== currentGeneration) return false
      const code = failure instanceof PagoServiceError ? failure.code
        : operation === 'write' ? 'CREATE_FAILED' : 'LOAD_FAILED'
      publish({ status: 'error', pago: previous, error: new PagoServiceError(code), operation, idempotencyKey: state.idempotencyKey })
      return false
    } finally {
      if (generation === currentGeneration) request = null
    }
  }

  return {
    getSnapshot: () => state,
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener) },
    connect(nextAdapter) {
      cancelPending()
      adapter = nextAdapter
      publish({ status: 'idle', pago: null, error: null, operation: null, idempotencyKey: null })
    },
    load: pedidoId => run('load', pedidoId),
    registrar: draft => run('write', { draft }),
    clearError() {
      if (!request && state.error && state.operation === 'write') {
        publish({ status: state.pago ? 'ready' : 'empty', pago: state.pago, error: null, operation: null, idempotencyKey: state.idempotencyKey })
      }
    },
    cancelPending,
  }
}
