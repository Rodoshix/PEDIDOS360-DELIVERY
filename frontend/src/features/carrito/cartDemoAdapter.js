import { createExampleCart, createEmptyExampleCart } from './cartDemo.js'
import { CART_CATALOG } from './cartCatalogDemo.js'
import { addCartProduct, changeCartQuantity, removeCartProduct, clearCart, CartValidationError } from './cartOperations.js'
import { CART_ERROR_MESSAGES, CartServiceError } from './cartErrors.js'

export const CART_SCENARIOS = Object.freeze([
  { value: 'example', label: 'Carrito con productos' },
  { value: 'empty', label: 'Carrito vacío' },
  { value: 'load-error', label: 'Consulta falla una vez' },
  { value: 'write-error', label: 'Operación falla una vez' },
  { value: 'conflict', label: 'Conflicto una vez' },
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

export function createCartDemoAdapter({ scenario = 'example', delayMs = 600 } = {}) {
  if (!CART_SCENARIOS.some(item => item.value === scenario)) throw new Error('Escenario de carrito desconocido')
  let cart = scenario === 'empty' ? createEmptyExampleCart() : createExampleCart()
  let readFailed = false
  let writeFailed = false
  const copy = () => ({ ...cart, items: cart.items.map(item => ({ ...item })) })
  return {
    async read({ signal } = {}) {
      await delay(delayMs, signal)
      signal?.throwIfAborted()
      if (scenario === 'forbidden') throw new CartServiceError('FORBIDDEN')
      if (scenario === 'load-error' && !readFailed) {
        readFailed = true
        throw new CartServiceError('LOAD_FAILED')
      }
      return copy()
    },
    async write(command, { signal } = {}) {
      await delay(delayMs, signal)
      signal?.throwIfAborted()
      if (scenario === 'forbidden') throw new CartServiceError('FORBIDDEN')
      if (['write-error', 'conflict'].includes(scenario) && !writeFailed) {
        writeFailed = true
        throw new CartServiceError(scenario === 'conflict' ? 'CONFLICT' : 'WRITE_FAILED')
      }
      try {
        let next
        switch (command.type) {
          case 'add': next = addCartProduct(cart, CART_CATALOG.find(item => item.productoId === command.productoId), command.cantidad); break
          case 'quantity': next = changeCartQuantity(cart, command.productoId, command.cantidad); break
          case 'remove': next = removeCartProduct(cart, command.productoId); break
          case 'clear': next = clearCart(cart); break
          default: throw new CartServiceError('INVALID_COMMAND')
        }
        cart = next
        return copy()
      } catch (error) {
        if (error instanceof CartValidationError) {
          const code = Object.keys(CART_ERROR_MESSAGES).find(key => CART_ERROR_MESSAGES[key] === error.message)
          throw new CartServiceError(code || 'WRITE_FAILED')
        }
        throw error
      }
    },
  }
}
