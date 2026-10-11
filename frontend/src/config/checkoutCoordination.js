/** Build configuration only: never URL, storage, DOM attributes or response payload. */
export function parseCheckoutCoordination(value) {
  const mode = value === undefined ? 'HTTP' : value
  if (mode !== 'HTTP' && mode !== 'RABBITMQ') throw new Error('VITE_PEDIDOS_CARRITO_MODE debe ser HTTP o RABBITMQ.')
  return mode
}

export const checkoutCoordination = parseCheckoutCoordination(import.meta.env?.VITE_PEDIDOS_CARRITO_MODE)
