export const CART_ERROR_MESSAGES = Object.freeze({
  NOT_CONFIGURED: 'El servicio de Carrito no está conectado.',
  LOAD_FAILED: 'No se pudo consultar el carrito de prueba. Puedes reintentar la consulta.',
  WRITE_FAILED: 'No se pudo modificar el carrito de prueba. Se conservan los datos y puedes reintentar manualmente.',
  CONFLICT: 'Conflicto simulado: no se aplicó la operación. Revisa los datos antes de reintentar.',
  FORBIDDEN: 'Este escenario no permite acceder ni modificar el carrito de prueba.',
  INVALID_RESPONSE: 'El servicio devolvió un carrito inválido. No se aplicó esa respuesta.',
  INVALID_COMMAND: 'La operación del carrito no es válida.',
  QUANTITY: 'La cantidad debe ser un entero entre 1 y 99.',
  PRODUCT: 'Los datos del producto de prueba no son válidos.',
  UNAVAILABLE: 'Este producto de prueba no está disponible.',
  RESTAURANT: 'Solo puedes agregar productos del mismo restaurante. Vacía el ejemplo antes de cambiar de restaurante.',
  LIMIT: 'El carrito admite hasta 50 productos diferentes.',
  NOT_FOUND: 'Ese producto no está en el ejemplo.',
})

export class CartServiceError extends Error {
  constructor(code) {
    const safeCode = Object.hasOwn(CART_ERROR_MESSAGES, code) ? code : 'WRITE_FAILED'
    super(CART_ERROR_MESSAGES[safeCode])
    this.name = 'CartServiceError'
    this.code = safeCode
  }
}
