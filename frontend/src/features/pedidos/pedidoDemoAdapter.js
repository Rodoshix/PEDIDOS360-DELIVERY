import { createEmptyPedidos, createExamplePedidos, PEDIDO_CATALOGO_DEMO } from './pedidoDemo.js'
import { puedeTransicionar } from './pedidoOperaciones.js'
import { PedidoServiceError } from './pedidoErrors.js'

export const PEDIDO_SCENARIOS = Object.freeze([
  { value: 'example', label: 'Pedidos con historial' },
  { value: 'empty', label: 'Sin pedidos' },
  { value: 'load-error', label: 'Consulta falla una vez' },
  { value: 'create-error', label: 'Creación falla una vez' },
  { value: 'transition-error', label: 'Transición falla una vez' },
  { value: 'conflict', label: 'Conflicto de estado una vez' },
  { value: 'forbidden', label: 'Acceso denegado' },
].map(Object.freeze))

function delay(ms, signal) {
  return new Promise((resolve, reject) => {
    const abort = () => { clearTimeout(timer); reject(new DOMException('Operación cancelada', 'AbortError')) }
    const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve() }, ms)
    if (signal?.aborted) abort()
    else signal?.addEventListener('abort', abort, { once: true })
  })
}

/**
 * Adapter demo de Pedidos: aplica las mismas reglas que pedidos-service
 * (creación desde catálogo, precio copiado, máquina de estados) sobre datos ficticios.
 */
export function createPedidoDemoAdapter({ scenario = 'example', delayMs = 600 } = {}) {
  if (!PEDIDO_SCENARIOS.some(item => item.value === scenario)) throw new Error('Escenario de Pedidos desconocido')
  let pedidos = scenario === 'empty' ? createEmptyPedidos() : createExamplePedidos()
  let nextPedidoId = 600
  let nextLineaId = 100
  let readFailed = false
  let writeFailed = false

  const copy = pedido => ({ ...pedido, lineas: pedido.lineas.map(linea => ({ ...linea })) })

  return {
    async list({ signal } = {}) {
      await delay(delayMs, signal)
      signal?.throwIfAborted()
      if (scenario === 'forbidden') throw new PedidoServiceError('FORBIDDEN')
      if (scenario === 'load-error' && !readFailed) {
        readFailed = true
        throw new PedidoServiceError('LOAD_FAILED')
      }
      return pedidos.map(copy)
    },

    async get(pedidoId, { signal } = {}) {
      await delay(delayMs, signal)
      signal?.throwIfAborted()
      if (scenario === 'forbidden') throw new PedidoServiceError('FORBIDDEN')
      const pedido = pedidos.find(item => item.pedidoId === pedidoId)
      if (!pedido) throw new PedidoServiceError('NOT_FOUND')
      return copy(pedido)
    },

    async write(command, { signal } = {}) {
      await delay(delayMs, signal)
      signal?.throwIfAborted()
      if (scenario === 'forbidden') throw new PedidoServiceError('FORBIDDEN')
      if (['create-error', 'conflict'].includes(scenario) && command.type === 'create' && !writeFailed) {
        writeFailed = true
        throw new PedidoServiceError(scenario === 'conflict' ? 'CONFLICT' : 'CREATE_FAILED')
      }
      if (['transition-error', 'conflict'].includes(scenario) && command.type === 'transition' && !writeFailed) {
        writeFailed = true
        throw new PedidoServiceError(scenario === 'conflict' ? 'CONFLICT' : 'TRANSITION_FAILED')
      }

      if (command.type === 'create') {
        const lineas = command.draft.items.map(item => {
          const producto = PEDIDO_CATALOGO_DEMO.find(candidato => candidato.productoId === item.productoId)
          if (!producto) throw new PedidoServiceError('INVALID_REQUEST')
          return {
            lineaId: nextLineaId++, productoId: producto.productoId, cantidad: item.cantidad,
            precioUnitario: producto.precioUnitario, subtotal: producto.precioUnitario * item.cantidad,
          }
        })
        const total = lineas.reduce((sum, linea) => sum + linea.subtotal, 0)
        const pedido = {
          pedidoId: nextPedidoId++, usuarioId: 10, restauranteId: command.draft.restauranteId,
          direccionEntrega: command.draft.direccionEntrega, estado: 'CREADO', moneda: 'CLP',
          total, fechaCreacion: new Date().toISOString(), lineas,
        }
        pedidos = [pedido, ...pedidos]
        return copy(pedido)
      }

      const pedido = pedidos.find(item => item.pedidoId === command.pedidoId)
      if (!pedido) throw new PedidoServiceError('NOT_FOUND')
      if (!puedeTransicionar(pedido.estado, command.estado)) throw new PedidoServiceError('CONFLICT')
      pedido.estado = command.estado
      return copy(pedido)
    },
  }
}
