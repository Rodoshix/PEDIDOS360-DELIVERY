import { MAX_ITEMS, MAX_PRICE, MAX_QUANTITY, esEstadoValido, normalizePedidoDraft, validatePedidoDraft } from './pedidoOperaciones.js'
import { PedidoServiceError } from './pedidoErrors.js'

const positiveId = value => Number.isSafeInteger(value) && value > 0
const invalidResponse = () => { throw new PedidoServiceError('INVALID_RESPONSE') }
const isDate = value => typeof value === 'string' && Number.isFinite(Date.parse(value))

// Proyección defensiva de una línea: campos conocidos, importes coherentes e inmutables.
function safeLinea(value) {
  if (!value || !positiveId(value.lineaId) || !positiveId(value.productoId)
    || !Number.isInteger(value.cantidad) || value.cantidad < 1 || value.cantidad > MAX_QUANTITY
    || !Number.isSafeInteger(value.precioUnitario) || value.precioUnitario < 0 || value.precioUnitario > MAX_PRICE
    || value.subtotal !== value.precioUnitario * value.cantidad) invalidResponse()
  return Object.freeze({
    lineaId: value.lineaId, productoId: value.productoId, cantidad: value.cantidad,
    precioUnitario: value.precioUnitario, subtotal: value.subtotal,
  })
}

// Proyección defensiva del pedido: estado válido, total coherente con las líneas.
export function validatePedidoResponse(value) {
  if (!value || !positiveId(value.pedidoId) || !positiveId(value.usuarioId) || !positiveId(value.restauranteId)
    || !esEstadoValido(value.estado) || value.moneda !== 'CLP'
    || typeof value.direccionEntrega !== 'string' || !value.direccionEntrega.trim()
    || !isDate(value.fechaCreacion) || !Array.isArray(value.lineas) || value.lineas.length === 0
    || value.lineas.length > MAX_ITEMS) invalidResponse()
  const lineas = value.lineas.map(safeLinea)
  const total = lineas.reduce((sum, linea) => sum + linea.subtotal, 0)
  if (!Number.isSafeInteger(value.total) || value.total !== total) invalidResponse()
  return Object.freeze({
    pedidoId: value.pedidoId, usuarioId: value.usuarioId, restauranteId: value.restauranteId,
    direccionEntrega: value.direccionEntrega.trim(), estado: value.estado, moneda: 'CLP',
    total, fechaCreacion: value.fechaCreacion, lineas: Object.freeze(lineas),
  })
}

function normalizeCommand(command) {
  if (!command || !['create', 'transition'].includes(command.type)) throw new PedidoServiceError('INVALID_COMMAND')
  if (command.type === 'transition') {
    if (!positiveId(command.pedidoId) || !esEstadoValido(command.estado)) throw new PedidoServiceError('INVALID_COMMAND')
    return Object.freeze({ type: 'transition', pedidoId: command.pedidoId, estado: command.estado })
  }
  if (Object.keys(validatePedidoDraft(command.draft)).length) throw new PedidoServiceError('INVALID_REQUEST')
  return Object.freeze({ type: 'create', draft: normalizePedidoDraft(command.draft) })
}

/**
 * Controller por pantalla/cuenta. No conoce MSAL, HTTP ni almacenamiento:
 * recibe un adapter (demo o real) y publica un estado inmutable.
 */
export function createPedidoController() {
  let state = Object.freeze({ status: 'idle', pedidos: null, pedido: null, error: null, operation: null })
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
    const previous = { pedidos: state.pedidos, pedido: state.pedido }
    publish({ ...state, status: operation === 'load' ? 'loading' : 'saving', error: null, operation })
    try {
      abort.signal.throwIfAborted()
      if (!currentAdapter) throw new PedidoServiceError('NOT_CONFIGURED')
      let result
      switch (operation) {
        case 'load': result = await currentAdapter.list({ signal: abort.signal }); break
        case 'detail': result = await currentAdapter.get(payload, { signal: abort.signal }); break
        case 'write': result = await currentAdapter.write(normalizeCommand(payload.command), { signal: abort.signal }); break
        default: throw new PedidoServiceError('INVALID_COMMAND')
      }
      if (abort.signal.aborted || generation !== currentGeneration) return false
      if (operation === 'load') {
        if (!Array.isArray(result)) invalidResponse()
        const pedidos = result.map(validatePedidoResponse)
        publish({ status: pedidos.length ? 'ready' : 'empty', pedidos: Object.freeze(pedidos), pedido: previous.pedido, error: null, operation: null })
      } else if (operation === 'detail') {
        publish({ status: 'ready', pedidos: previous.pedidos, pedido: validatePedidoResponse(result), error: null, operation: null })
      } else {
        // write (create|transition): devuelve siempre el pedido actualizado.
        // No se presenta como fallo una escritura aplicada: se valida el pedido y se
        // reemplaza en la lista (o se fija como detalle si showDetail) para conservar coherencia.
        const pedido = validatePedidoResponse(result)
        const pedidos = previous.pedidos
          ? Object.freeze(previous.pedidos.map(item => item.pedidoId === pedido.pedidoId ? pedido : item))
          : previous.pedidos
        publish({ status: 'ready', pedidos, pedido: payload.showDetail ? pedido : (previous.pedido?.pedidoId === pedido.pedidoId ? pedido : previous.pedido), error: null, operation: null })
      }
      return true
    } catch (failure) {
      if (abort.signal.aborted || generation !== currentGeneration) return false
      // Recrear el error: nunca propagar mensajes ni causas del adaptador.
      const code = failure instanceof PedidoServiceError ? failure.code
        : operation === 'write' ? 'CREATE_FAILED' : 'LOAD_FAILED'
      publish({ status: 'error', ...previous, error: new PedidoServiceError(code), operation })
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
      publish({ status: 'idle', pedidos: null, pedido: null, error: null, operation: null })
    },
    load: () => run('load'),
    detail: pedidoId => run('detail', pedidoId),
    create: (draft, { showDetail = true } = {}) => run('write', { command: { type: 'create', draft }, showDetail }),
    transition: (pedidoId, estado, { showDetail = false } = {}) => run('write', { command: { type: 'transition', pedidoId, estado }, showDetail }),
    clearError() {
      if (!request && state.error && state.operation === 'write') {
        publish({ status: state.pedidos?.length ? 'ready' : 'empty', pedidos: state.pedidos, pedido: state.pedido, error: null, operation: null })
      }
    },
    cancelPending,
  }
}
