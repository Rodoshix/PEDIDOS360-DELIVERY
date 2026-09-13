export const CART_ERROR_MESSAGES = Object.freeze({
  NOT_CONFIGURED: 'El servicio de Carrito no está conectado.',
  LOAD_FAILED: 'No se pudo consultar el servicio. Puedes reintentar la consulta.',
  WRITE_FAILED: 'No se pudo confirmar la operación. Consulta el carrito antes de repetir: el servidor pudo haber aplicado el cambio.',
  CONFLICT: 'La operación entró en conflicto. Consulta el carrito y revisa los productos antes de continuar.',
  FORBIDDEN: 'No tienes permiso para acceder o modificar estos datos.',
  UNAUTHORIZED: 'La API rechazó la sesión. Vuelve a iniciar sesión con Microsoft.',
  INTERACTION_REQUIRED: 'Microsoft necesita confirmar el acceso a la API.',
  RECONCILE: 'Consulta el carrito para confirmar su estado antes de enviar otra operación.',
  INVALID_RESPONSE: 'El servicio devolvió un carrito inválido. No se aplicó esa respuesta.',
  INVALID_COMMAND: 'La operación del carrito no es válida.',
  QUANTITY: 'La cantidad debe ser un entero entre 1 y 99.',
  PRODUCT: 'Los datos del producto de prueba no son válidos.',
  UNAVAILABLE: 'Este producto de prueba no está disponible.',
  RESTAURANT: 'Solo puedes agregar productos del mismo restaurante. Vacía el ejemplo antes de cambiar de restaurante.',
  LIMIT: 'El carrito admite hasta 50 productos diferentes.',
  NOT_FOUND: 'El recurso solicitado no está disponible. Vuelve a consultar.',
})

export class CartServiceError extends Error {
  constructor(code) {
    const safeCode = Object.hasOwn(CART_ERROR_MESSAGES, code) ? code : 'WRITE_FAILED'
    super(CART_ERROR_MESSAGES[safeCode])
    this.name = 'CartServiceError'
    this.code = safeCode
  }
}
