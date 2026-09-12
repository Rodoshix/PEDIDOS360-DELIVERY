import { PagoServiceError } from './pagoErrors.js'

export const PAGO_SCENARIOS = Object.freeze([
  { value: 'tarjeta', label: 'Tarjeta (aprobado)' },
  { value: 'efectivo', label: 'Efectivo (pendiente de cobro)' },
  { value: 'rechazado', label: 'Tarjeta rechazada' },
  { value: 'conflict', label: 'Pago activo duplicado' },
  { value: 'load-error', label: 'Consulta falla una vez' },
  { value: 'create-error', label: 'Registro falla una vez' },
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
 * Adapter demo de Pagos: replica el flujo de pagos-service.
 * - TARJETA: APROBADO (y confirma el pedido) o RECHAZADO.
 * - EFECTIVO: PENDIENTE de cobro (se aprueba al entregar).
 * - Idempotencia por clave: el mismo intento devuelve el mismo pago.
 */
export function createPagoDemoAdapter({ scenario = 'tarjeta', pedido = null, delayMs = 600 } = {}) {
  if (!PAGO_SCENARIOS.some(item => item.value === scenario)) throw new Error('Escenario de Pagos desconocido')
  const idempotencia = new Map()
  let nextPagoId = 700
  let readFailed = false
  let writeFailed = false

  const estadoInicial = metodo => metodo === 'EFECTIVO' ? 'PENDIENTE' : 'APROBADO'

  return {
    async get(pedidoId, { signal } = {}) {
      await delay(delayMs, signal)
      signal?.throwIfAborted()
      if (scenario === 'forbidden') throw new PagoServiceError('FORBIDDEN')
      if (scenario === 'load-error' && !readFailed) {
        readFailed = true
        throw new PagoServiceError('LOAD_FAILED')
      }
      const encontrado = [...idempotencia.values()].find(pago => pago.pedidoId === pedidoId)
      if (!encontrado) throw new PagoServiceError('NOT_FOUND')
      return { ...encontrado }
    },

    async create(draft, clave, { signal } = {}) {
      await delay(delayMs, signal)
      signal?.throwIfAborted()
      if (scenario === 'forbidden') throw new PagoServiceError('FORBIDDEN')
      if (idempotencia.has(clave)) return { ...idempotencia.get(clave) }
      if (scenario === 'conflict') throw new PagoServiceError('CONFLICT')
      if (scenario === 'create-error' && !writeFailed) {
        writeFailed = true
        throw new PagoServiceError('CREATE_FAILED')
      }
      const total = pedido?.total ?? 12500
      const estado = scenario === 'rechazado' && draft.metodo === 'TARJETA'
        ? 'RECHAZADO'
        : estadoInicial(draft.metodo)
      const pago = {
        pagoId: nextPagoId++, pedidoId: draft.pedidoId, usuarioId: 10, monto: total,
        moneda: 'CLP', metodo: draft.metodo, estado, fecha: new Date().toISOString(),
      }
      idempotencia.set(clave, pago)
      return { ...pago }
    },
  }
}
