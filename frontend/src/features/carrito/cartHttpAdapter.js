import { ApiAccessError } from '../../auth/ApiAccessError.js'
import { CartServiceError } from './cartErrors.js'
import { validateCartResponse } from './cartService.js'
import { MAX_PRICE, parseQuantity } from './cartOperations.js'

export function commerceFailure(error, fallback = 'LOAD_FAILED') {
  if (error instanceof CartServiceError) return new CartServiceError(error.code)
  if (error instanceof ApiAccessError) {
    if (error.code === 'INTERACTION_REQUIRED') return new CartServiceError('INTERACTION_REQUIRED')
    if (error.status === 401 || ['SESSION_REQUIRED', 'SESSION_CHANGED'].includes(error.code)) return new CartServiceError('UNAUTHORIZED')
    if (error.status === 403) return new CartServiceError('FORBIDDEN')
    if (error.status === 404) return new CartServiceError('NOT_FOUND')
    if (error.status === 409) return new CartServiceError('CONFLICT')
    if (error.status === 400) return new CartServiceError('INVALID_COMMAND')
  }
  return new CartServiceError(fallback)
}
const positive = id => Number.isSafeInteger(id) && id > 0

export function createCartHttpAdapter(client) {
  let loaded = false
  async function read({ signal } = {}) {
    loaded = false
    try {
      signal?.throwIfAborted()
      const response = await client.get('/carrito', { signal })
      signal?.throwIfAborted()
      if (response.status !== 200) throw new CartServiceError('INVALID_RESPONSE')
      const cart = validateCartResponse(response.data)
      loaded = true
      return cart
    } catch (error) { throw commerceFailure(error) }
  }
  return {
    read,
    async write(command, { signal } = {}) {
      if (!loaded) throw new CartServiceError('RECONCILE')
      if (!command || !['add', 'quantity', 'remove', 'clear'].includes(command.type)
        || (command.type !== 'clear' && !positive(command.productoId))) throw new CartServiceError('INVALID_COMMAND')
      const quantity = ['add', 'quantity'].includes(command.type) ? parseQuantity(command.cantidad) : null
      signal?.throwIfAborted()
      // Toda escritura requiere una respuesta confirmada antes de permitir otra.
      loaded = false
      try {
        let response
        const path = '/carrito/items/' + command.productoId
        if (command.type === 'add') response = await client.post('/carrito/items', { productoId: command.productoId, cantidad: quantity }, { signal })
        else if (command.type === 'quantity') response = await client.put(path, { cantidad: quantity }, { signal })
        else response = await client.delete(command.type === 'clear' ? '/carrito' : path, { signal })
        signal?.throwIfAborted()
        if (['remove', 'clear'].includes(command.type)) {
          if (response.status !== 204) throw new CartServiceError('INVALID_RESPONSE')
          return await read({ signal }) // Confirmar estado; nunca repetir DELETE.
        }
        if (response.status !== 200) throw new CartServiceError('INVALID_RESPONSE')
        const cart = validateCartResponse(response.data)
        loaded = true
        return cart
      } catch (error) { throw commerceFailure(error, 'WRITE_FAILED') }
    },
  }
}

export function createCatalogHttpAdapter(client) {
  async function list(path, validate, signal) {
    try {
      signal?.throwIfAborted()
      const response = await client.get(path, { signal })
      signal?.throwIfAborted()
      if (response.status !== 200 || !Array.isArray(response.data)) throw new CartServiceError('INVALID_RESPONSE')
      const ids = new Set()
      return Object.freeze(response.data.map(value => {
        const item = validate(value)
        if (ids.has(item.id)) throw new CartServiceError('INVALID_RESPONSE')
        ids.add(item.id)
        return Object.freeze(item)
      }))
    } catch (error) { throw commerceFailure(error) }
  }
  function base(value) {
    if (!value || !positive(value.id) || typeof value.nombre !== 'string' || !value.nombre.trim())
      throw new CartServiceError('INVALID_RESPONSE')
    return { id: value.id, nombre: value.nombre.trim() }
  }
  return {
    restaurants: ({ signal } = {}) => list('/restaurantes', value => {
      const item = base(value)
      if (!['ABIERTO', 'CERRADO', 'INACTIVO'].includes(value.estado)) throw new CartServiceError('INVALID_RESPONSE')
      return { ...item, estado: value.estado }
    }, signal),
    products: (restaurantId, { signal } = {}) => {
      if (!positive(restaurantId)) throw new CartServiceError('INVALID_COMMAND')
      return list('/productos/restaurante/' + restaurantId, value => {
        const item = base(value)
        if (value.restauranteId !== restaurantId || typeof value.disponible !== 'boolean'
          || !Number.isSafeInteger(value.precio) || value.precio < 0 || value.precio > MAX_PRICE) throw new CartServiceError('INVALID_RESPONSE')
        return { ...item, restauranteId: restaurantId, precio: value.precio, disponible: value.disponible }
      }, signal)
    },
  }
}
