import { ApiAccessError } from '../../auth/ApiAccessError.js'
import { validatePedidoResponse } from './pedidoService.js'
import { PedidoServiceError } from './pedidoErrors.js'

/**
 * Traduce un fallo del cliente autenticado o del dominio a un error seguro de Pedidos.
 * Nunca propaga mensajes ni causas del transporte.
 */
export function pedidoFailure(error, fallback = 'LOAD_FAILED') {
  if (error instanceof PedidoServiceError) return new PedidoServiceError(error.code)
  if (error instanceof ApiAccessError) {
    if (error.code === 'INTERACTION_REQUIRED') return new PedidoServiceError('INTERACTION_REQUIRED')
    if (error.status === 401 || ['SESSION_REQUIRED', 'SESSION_CHANGED'].includes(error.code)) return new PedidoServiceError('UNAUTHORIZED')
    if (error.status === 403) return new PedidoServiceError('FORBIDDEN')
    if (error.status === 404) return new PedidoServiceError('NOT_FOUND')
    if (error.status === 409) return new PedidoServiceError('CONFLICT')
    if (error.status === 400) return new PedidoServiceError('INVALID_REQUEST')
  }
  return new PedidoServiceError(fallback)
}

const positive = id => Number.isSafeInteger(id) && id > 0

/** Comprueba que la respuesta sea 2xx y proyecta el pedido de forma defensiva. */
function pedido(response, esperado = 200) {
  if (!response || response.status !== esperado) throw new PedidoServiceError('INVALID_RESPONSE')
  return validatePedidoResponse(response.data)
}

/**
 * Adapter HTTP de Pedidos contra el BFF (Bearer delegado del usuario).
 * Rutas del contrato #47: POST /pedidos, GET /pedidos/me, GET /pedidos/{id},
 * PUT /pedidos/{id}/estado.
 */
export function createPedidoHttpAdapter(client) {
  return {
    async list({ signal } = {}) {
      try {
        signal?.throwIfAborted()
        const response = await client.get('/pedidos/me', { signal })
        signal?.throwIfAborted()
        if (response.status !== 200 || !Array.isArray(response.data)) throw new PedidoServiceError('INVALID_RESPONSE')
        return Object.freeze(response.data.map(value => validatePedidoResponse(value)))
      } catch (error) { throw pedidoFailure(error) }
    },

    async get(pedidoId, { signal } = {}) {
      if (!positive(pedidoId)) throw new PedidoServiceError('INVALID_COMMAND')
      try {
        signal?.throwIfAborted()
        const response = await client.get('/pedidos/' + pedidoId, { signal })
        signal?.throwIfAborted()
        return pedido(response)
      } catch (error) { throw pedidoFailure(error) }
    },

    async write(command, { signal } = {}) {
      if (!command || !['create', 'transition'].includes(command.type)) throw new PedidoServiceError('INVALID_COMMAND')
      try {
        signal?.throwIfAborted()
        let response
        if (command.type === 'create') {
          const draft = command.draft
          response = await client.post('/pedidos', {
            restauranteId: draft.restauranteId,
            direccionEntrega: draft.direccionEntrega,
            items: draft.items.map(item => ({ productoId: item.productoId, cantidad: item.cantidad })),
          }, { signal })
        } else {
          if (!positive(command.pedidoId)) throw new PedidoServiceError('INVALID_COMMAND')
          response = await client.put('/pedidos/' + command.pedidoId + '/estado',
            { estado: command.estado }, { signal })
        }
        signal?.throwIfAborted()
        return pedido(response, command.type === 'create' ? 201 : 200)
      } catch (error) { throw pedidoFailure(error, command.type === 'create' ? 'CREATE_FAILED' : 'TRANSITION_FAILED') }
    },
  }
}
