import { createAuthConfiguration } from '../src/auth/authConfiguration.js'

// Reutiliza la validación existente. No carga .env.local ni imprime valores.
try {
  createAuthConfiguration(process.env)
} catch (error) {
  console.error(`Configuración pública de Docker inválida: ${error.message}`)
  process.exitCode = 1
}
