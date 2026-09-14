// Reglas del ejemplo local; no sustituye la validación del servidor ni consulta Productos.
export const MAX_QUANTITY = 99
export const MAX_ITEMS = 50
export const MAX_PRICE = 1_000_000_000

export class CartValidationError extends Error {}

export function parseQuantity(value) {
  if ((typeof value !== 'number' && typeof value !== 'string')
    || (typeof value === 'string' && !/^\d+$/.test(value.trim()))) {
    throw new CartValidationError('La cantidad debe ser un entero entre 1 y 99.')
  }
  const quantity = Number(value)
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > MAX_QUANTITY) {
    throw new CartValidationError('La cantidad debe ser un entero entre 1 y 99.')
  }
  return quantity
}

function requireCart(cart) {
  if (!cart || !Array.isArray(cart.items) || cart.moneda !== 'CLP') {
    throw new CartValidationError('Elige primero un ejemplo de carrito.')
  }
}

function rebuild(cart, items, restauranteId) {
  const lines = items.map(item => ({ productoId: item.productoId, nombre: item.nombre,
    precioUnitario: item.precioUnitario, cantidad: item.cantidad,
    subtotal: item.precioUnitario * item.cantidad })).sort((a, b) => a.productoId - b.productoId)
  const total = lines.reduce((sum, item) => sum + item.subtotal, 0)
  if (!Number.isSafeInteger(total) || total < 0) throw new CartValidationError('El total del ejemplo no es válido.')
  return { id: cart.id ?? 1, restauranteId: lines.length ? restauranteId : null, moneda: 'CLP', total,
    version: (cart.version ?? -1) + 1, actualizadoEn: new Date().toISOString(), items: lines }
}

export function addCartProduct(cart, product, value) {
  requireCart(cart)
  const quantity = parseQuantity(value)
  if (!product || !Number.isSafeInteger(product.productoId) || product.productoId <= 0
    || !Number.isSafeInteger(product.restauranteId) || product.restauranteId <= 0
    || typeof product.nombre !== 'string' || !product.nombre.trim() || product.nombre.trim().length > 200
    || !Number.isSafeInteger(product.precioUnitario) || product.precioUnitario < 0 || product.precioUnitario > MAX_PRICE) {
    throw new CartValidationError('Los datos del producto de prueba no son válidos.')
  }
  if (product.disponible !== true) throw new CartValidationError('Este producto de prueba no está disponible.')
  if (cart.restauranteId !== null && cart.restauranteId !== product.restauranteId) {
    throw new CartValidationError('Solo puedes agregar productos del mismo restaurante. Vacía el ejemplo antes de cambiar de restaurante.')
  }
  const previous = cart.items.find(item => item.productoId === product.productoId)
  const nextQuantity = parseQuantity((previous?.cantidad ?? 0) + quantity)
  if (!previous && cart.items.length >= MAX_ITEMS) throw new CartValidationError('El carrito admite hasta 50 productos diferentes.')
  const line = { productoId: product.productoId, nombre: product.nombre.trim(),
    precioUnitario: product.precioUnitario, cantidad: nextQuantity }
  return rebuild(cart, [...cart.items.filter(item => item.productoId !== product.productoId), line], product.restauranteId)
}

export function changeCartQuantity(cart, productId, value) {
  requireCart(cart)
  const quantity = parseQuantity(value)
  const previous = cart.items.find(item => item.productoId === productId)
  if (!previous) throw new CartValidationError('Ese producto no está en el ejemplo.')
  if (previous.cantidad === quantity) return cart
  return rebuild(cart, cart.items.map(item => item.productoId === productId ? { ...item, cantidad: quantity } : item), cart.restauranteId)
}

export function removeCartProduct(cart, productId) {
  requireCart(cart)
  if (!cart.items.some(item => item.productoId === productId)) throw new CartValidationError('Ese producto no está en el ejemplo.')
  return rebuild(cart, cart.items.filter(item => item.productoId !== productId), cart.restauranteId)
}

export function clearCart(cart) {
  requireCart(cart)
  return cart.items.length ? rebuild(cart, [], null) : cart
}
