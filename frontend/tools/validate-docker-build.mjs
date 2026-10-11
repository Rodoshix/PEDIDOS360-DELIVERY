import { parseCheckoutCoordination } from '../src/config/checkoutCoordination.js'
import { createAuthConfiguration } from '../src/auth/authConfiguration.js'

// Reutiliza la validación existente. No carga .env.local ni imprime valores.
try {
  createAuthConfiguration(process.env)
  parseCheckoutCoordination(process.env.VITE_PEDIDOS_CARRITO_MODE)
} catch (error) {
  console.error(`Configuración pública de Docker inválida: ${error.message}`)
  process.exitCode = 1
}
