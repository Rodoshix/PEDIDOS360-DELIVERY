// Reglas del dominio de Pedidos para el frontend: estados, transiciones y validaciones.
// No conoce HTTP ni almacenamiento; solo forma y coherencia de los datos.

export const ESTADOS_PEDIDO = Object.freeze([
  'CREADO', 'CONFIRMADO', 'PREPARANDO', 'LISTO', 'EN_REPARTO', 'ENTREGADO', 'CANCELADO',
])

export const ESTADOS_TERMINALES = Object.freeze(['ENTREGADO', 'CANCELADO'])

// Transiciones válidas (espejo de la máquina de estados de pedidos-service).
const TRANSICIONES = Object.freeze({
  CREADO: Object.freeze(['CONFIRMADO', 'CANCELADO']),
  CONFIRMADO: Object.freeze(['PREPARANDO', 'CANCELADO']),
  PREPARANDO: Object.freeze(['LISTO', 'CANCELADO']),
  LISTO: Object.freeze(['EN_REPARTO']),
  EN_REPARTO: Object.freeze(['ENTREGADO']),
  ENTREGADO: Object.freeze([]),
  CANCELADO: Object.freeze([]),
})

export const ETIQUETAS_ESTADO = Object.freeze({
  CREADO: 'Creado',
  CONFIRMADO: 'Confirmado',
  PREPARANDO: 'En preparación',
  LISTO: 'Listo',
  EN_REPARTO: 'En reparto',
  ENTREGADO: 'Entregado',
  CANCELADO: 'Cancelado',
})

export const MAX_ITEMS = 50
export const MAX_QUANTITY = 99
export const MAX_PRICE = 10_000_000

export function esEstadoValido(estado) {
  return ESTADOS_PEDIDO.includes(estado)
}

export function transicionesPermitidas(estado) {
  return TRANSICIONES[estado] ?? Object.freeze([])
}

export function puedeTransicionar(desde, hacia) {
  return transicionesPermitidas(desde).includes(hacia)
}

const positiveId = value => Number.isSafeInteger(value) && value > 0

// Normaliza la entrada de creación y devuelve los errores de validación por campo.
export function validatePedidoDraft(draft) {
  const errores = {}
  if (!draft) return { restauranteId: 'Falta el restaurante.', direccionEntrega: 'Falta la dirección.', items: 'Agrega al menos un producto.' }
  if (!positiveId(draft.restauranteId)) errores.restauranteId = 'El restaurante no es válido.'
  const direccion = typeof draft.direccionEntrega === 'string' ? draft.direccionEntrega.trim() : ''
  if (!direccion) errores.direccionEntrega = 'Indica una dirección de entrega.'
  else if (direccion.length > 255) errores.direccionEntrega = 'La dirección no puede superar 255 caracteres.'
  if (!Array.isArray(draft.items) || draft.items.length === 0) errores.items = 'Agrega al menos un producto.'
  else if (draft.items.length > MAX_ITEMS) errores.items = `El pedido admite hasta ${MAX_ITEMS} productos.`
  else if (draft.items.some(item => !positiveId(item?.productoId)
    || !Number.isInteger(item?.cantidad) || item.cantidad < 1 || item.cantidad > MAX_QUANTITY)) {
    errores.items = 'Revisa el producto y la cantidad (entero entre 1 y 99).'
  }
  return errores
}

export function normalizePedidoDraft(draft) {
  return Object.freeze({
    restauranteId: draft.restauranteId,
    direccionEntrega: draft.direccionEntrega.trim(),
    items: Object.freeze(draft.items.map(item => Object.freeze({
      productoId: item.productoId, cantidad: item.cantidad,
    }))),
  })
}
