// Objetos nuevos por acción/cuenta. Contrato CarritoResponse, sin almacenamiento ni HTTP.
export function createEmptyExampleCart() {
  return { id: null, restauranteId: null, moneda: 'CLP', total: 0, version: null, actualizadoEn: null, items: [] }
}

export function createExampleCart() {
  return {
    id: 1, restauranteId: 101, moneda: 'CLP', total: 12500, version: 0,
    actualizadoEn: '2026-09-10T12:00:00Z',
    items: [
      { productoId: 1001, nombre: 'Hamburguesa de ejemplo', precioUnitario: 5500, cantidad: 2, subtotal: 11000 },
      { productoId: 1002, nombre: 'Bebida de ejemplo', precioUnitario: 1500, cantidad: 1, subtotal: 1500 },
    ],
  }
}
