// Catálogo independiente del backend, solo para probar restricciones de la pantalla.
export const CART_CATALOG = Object.freeze([
  { productoId: 1001, restauranteId: 101, restaurante: 'Restaurante A de prueba', nombre: 'Hamburguesa de ejemplo', precioUnitario: 5500, disponible: true },
  { productoId: 1002, restauranteId: 101, restaurante: 'Restaurante A de prueba', nombre: 'Bebida de ejemplo', precioUnitario: 1500, disponible: true },
  { productoId: 1003, restauranteId: 101, restaurante: 'Restaurante A de prueba', nombre: 'Postre no disponible de ejemplo', precioUnitario: 2500, disponible: false },
  { productoId: 2001, restauranteId: 102, restaurante: 'Restaurante B de prueba', nombre: 'Pizza de otro restaurante de ejemplo', precioUnitario: 8000, disponible: true },
].map(Object.freeze))
