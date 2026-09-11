import { MAX_ITEMS, MAX_PRICE, MAX_QUANTITY, parseQuantity } from './cartOperations.js'
import { CartServiceError } from './cartErrors.js'

const positiveId = value => Number.isSafeInteger(value) && value > 0
const invalidResponse = () => { throw new CartServiceError('INVALID_RESPONSE') }

// Proyección defensiva del DTO: importes coherentes, campos conocidos e inmutables.
export function validateCartResponse(value) {
  if (!value || !Array.isArray(value.items) || value.items.length > MAX_ITEMS || value.moneda !== 'CLP') invalidResponse()
  const empty = value.items.length === 0
  if (value.id === null) {
    if (!empty || value.version !== null || value.actualizadoEn !== null) invalidResponse()
  } else if (!positiveId(value.id) || !Number.isSafeInteger(value.version) || value.version < 0
    || typeof value.actualizadoEn !== 'string' || !Number.isFinite(Date.parse(value.actualizadoEn))) invalidResponse()
  if (empty ? value.restauranteId !== null : !positiveId(value.restauranteId)) invalidResponse()
  const seen = new Set()
  const items = value.items.map(item => {
    if (!item || !positiveId(item.productoId) || seen.has(item.productoId)
      || typeof item.nombre !== 'string' || !item.nombre.trim() || item.nombre.trim().length > 200
      || !Number.isInteger(item.cantidad) || item.cantidad < 1 || item.cantidad > MAX_QUANTITY
      || !Number.isSafeInteger(item.precioUnitario) || item.precioUnitario < 0 || item.precioUnitario > MAX_PRICE
      || item.subtotal !== item.precioUnitario * item.cantidad) invalidResponse()
    seen.add(item.productoId)
    return Object.freeze({ productoId: item.productoId, nombre: item.nombre.trim(), precioUnitario: item.precioUnitario,
      cantidad: item.cantidad, subtotal: item.subtotal })
  })
  const total = items.reduce((sum, item) => sum + item.subtotal, 0)
  if (!Number.isSafeInteger(value.total) || value.total !== total) invalidResponse()
  return Object.freeze({ id: value.id, restauranteId: value.restauranteId, moneda: 'CLP', total,
    version: value.version, actualizadoEn: value.actualizadoEn, items: Object.freeze(items) })
}

function normalizeCommand(command) {
  if (!command || !['add', 'quantity', 'remove', 'clear'].includes(command.type)) throw new CartServiceError('INVALID_COMMAND')
  if (command.type === 'clear') return Object.freeze({ type: 'clear' })
  if (!positiveId(command.productoId)) throw new CartServiceError('INVALID_COMMAND')
  if (command.type === 'remove') return Object.freeze({ type: 'remove', productoId: command.productoId })
  let cantidad
  try { cantidad = parseQuantity(command.cantidad) } catch { throw new CartServiceError('QUANTITY') }
  return Object.freeze({ type: command.type, productoId: command.productoId, cantidad })
}

export function createCartController() {
  let state = Object.freeze({ status: 'idle', cart: null, error: null, operation: null })
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
  async function run(operation, command) {
    if (request) return false
    const currentGeneration = ++generation
    const abort = new AbortController()
    request = abort
    const currentAdapter = adapter
    const previous = state.cart
    publish({ ...state, status: operation === 'read' ? 'loading' : 'saving', error: null, operation })
    try {
      abort.signal.throwIfAborted()
      if (!currentAdapter) throw new CartServiceError('NOT_CONFIGURED')
      const result = operation === 'read' ? await currentAdapter.read({ signal: abort.signal })
        : await currentAdapter.write(normalizeCommand(command), { signal: abort.signal })
      if (abort.signal.aborted || generation !== currentGeneration) return false
      const cart = validateCartResponse(result)
      publish({ status: cart.items.length ? 'ready' : 'empty', cart, error: null, operation: null })
      return true
    } catch (failure) {
      if (abort.signal.aborted || generation !== currentGeneration) return false
      const error = new CartServiceError(failure instanceof CartServiceError ? failure.code
        : operation === 'read' ? 'LOAD_FAILED' : 'WRITE_FAILED')
      publish({ status: 'error', cart: previous, error, operation })
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
      publish({ status: 'idle', cart: null, error: null, operation: null })
    },
    load: () => run('read'),
    write: command => !state.cart || (state.operation === 'read' && state.status === 'error')
      ? Promise.resolve(false) : run('write', command),
    clearWriteError() {
      if (!request && state.error && state.operation === 'write') {
        publish({ status: state.cart.items.length ? 'ready' : 'empty', cart: state.cart, error: null, operation: null })
      }
    },
    cancelPending,
  }
}
