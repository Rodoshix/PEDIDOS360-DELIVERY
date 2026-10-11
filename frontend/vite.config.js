import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig, loadEnv } from 'vite'
import { parseCheckoutCoordination } from './src/config/checkoutCoordination.js'

// Configuración incorporada al artefacto; no se toma de parámetros del navegador.
export default defineConfig(({ mode }) => {
  parseCheckoutCoordination(loadEnv(mode, process.cwd(), 'VITE_').VITE_PEDIDOS_CARRITO_MODE)
  return {
    plugins: [react(), tailwindcss()],
    server: {
      host: 'localhost',
      port: 5173,
      strictPort: true,
    },
    preview: {
      host: 'localhost',
      port: 5173,
      strictPort: true,
    },
  }
})
