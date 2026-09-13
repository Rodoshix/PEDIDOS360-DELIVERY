// Datos ficticios del dominio Pedidos, independientes del backend.
// Solo para pruebas de la pantalla: no representan pedidos reales.

export function createExamplePedidos() {
  return [
    {
      pedidoId: 501, usuarioId: 10, restauranteId: 101, direccionEntrega: 'Av. Ejemplo 123, Depto 4B',
      estado: 'CREADO', moneda: 'CLP', total: 12500, fechaCreacion: '2026-09-10T12:00:00Z',
      lineas: [
        { lineaId: 1, productoId: 1001, cantidad: 2, precioUnitario: 5500, subtotal: 11000 },
        { lineaId: 2, productoId: 1002, cantidad: 1, precioUnitario: 1500, subtotal: 1500 },
      ],
    },
    {
      pedidoId: 502, usuarioId: 10, restauranteId: 101, direccionEntrega: 'Av. Ejemplo 123, Depto 4B',
      estado: 'PREPARANDO', moneda: 'CLP', total: 8000, fechaCreacion: '2026-09-09T19:30:00Z',
      lineas: [
        { lineaId: 3, productoId: 1001, cantidad: 1, precioUnitario: 5500, subtotal: 5500 },
        { lineaId: 4, productoId: 1003, cantidad: 1, precioUnitario: 2500, subtotal: 2500 },
      ],
    },
    {
      pedidoId: 503, usuarioId: 10, restauranteId: 102, direccionEntrega: 'Calle Ficticia 456',
      estado: 'ENTREGADO', moneda: 'CLP', total: 8000, fechaCreacion: '2026-09-05T13:15:00Z',
      lineas: [
        { lineaId: 5, productoId: 2001, cantidad: 1, precioUnitario: 8000, subtotal: 8000 },
      ],
    },
  ]
}

export function createEmptyPedidos() {
  return []
}

// Catálogo ficticio para armar el pedido de prueba (confirmación desde carrito).
export const PEDIDO_CATALOGO_DEMO = Object.freeze([
  { productoId: 1001, restauranteId: 101, restaurante: 'Restaurante A de prueba', nombre: 'Hamburguesa de ejemplo', precioUnitario: 5500, disponible: true },
  { productoId: 1002, restauranteId: 101, restaurante: 'Restaurante A de prueba', nombre: 'Bebida de ejemplo', precioUnitario: 1500, disponible: true },
  { productoId: 1003, restauranteId: 101, restaurante: 'Restaurante A de prueba', nombre: 'Postre de ejemplo', precioUnitario: 2500, disponible: true },
  { productoId: 2001, restauranteId: 102, restaurante: 'Restaurante B de prueba', nombre: 'Pizza de otro restaurante de ejemplo', precioUnitario: 8000, disponible: true },
].map(Object.freeze))

export const RESTAURANTES_DEMO = Object.freeze([
  { restauranteId: 101, nombre: 'Restaurante A de prueba' },
  { restauranteId: 102, nombre: 'Restaurante B de prueba' },
].map(Object.freeze))
