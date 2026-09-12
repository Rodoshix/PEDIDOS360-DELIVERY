import { useState } from 'react'
import { useLocation } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import { ApiAccessError } from '../../auth/ApiAccessError.js'

export default function ApiPermissionCheck() {
  const { busy, checkApiAccess, authorizeApi } = useAuthSession()
  const location = useLocation()
  const [checking, setChecking] = useState(false)
  const [result, setResult] = useState(null)

  async function check() {
    if (checking || busy) return
    setChecking(true)
    setResult(null)
    try {
      await checkApiAccess()
      setResult({ message: 'Microsoft entregó un token para nuestra API. Falta comprobar su aceptación en el backend.' })
    } catch (error) {
      const safeError = error instanceof ApiAccessError ? error : new ApiAccessError('TOKEN_UNAVAILABLE')
      setResult({ message: safeError.message, error: true, interaction: safeError.code === 'INTERACTION_REQUIRED' })
    } finally { setChecking(false) }
  }

  async function checkBackend() {
    if (checking || busy) return
    setChecking(true)
    setResult(null)
    try {
      const { default: httpClient } = await import('../../services/httpClient.js')
      const response = await httpClient.get('/usuarios/me')
      setResult({ message: `BFF → Usuarios: HTTP ${response.status}. Token aceptado y perfil encontrado.` })
    } catch (error) {
      const safeError = error instanceof ApiAccessError ? error : new ApiAccessError('API_NETWORK')
      if (safeError.status === 404) {
        setResult({ message: 'BFF → Usuarios: HTTP 404. La consulta llegó al backend; no existe un perfil para esta cuenta en la base de prueba.' })
      } else {
        setResult({ message: safeError.message, error: true, interaction: safeError.code === 'INTERACTION_REQUIRED' })
      }
    } finally {
      setChecking(false)
    }
  }

  return (
    <details className="profile-diagnostics">
      <summary>Diagnóstico de acceso a la API</summary>
      <p className="account-note">Comprobar permiso solo solicita acceso a Microsoft; no consulta ni guarda tu perfil.</p>
      {import.meta.env.DEV && <p className="account-note">Probar BFF y Usuarios consulta tu perfil real, pero solo muestra el resultado HTTP. No guarda datos ni muestra el token.</p>}
      <button type="button" className="button button--primary session-controls__button" disabled={checking || busy} onClick={check}>
        {checking ? 'Comprobando permiso…' : 'Comprobar permiso de API'}
      </button>
      {import.meta.env.DEV && <button type="button" className="button button--secondary session-controls__button" disabled={checking || busy} onClick={checkBackend}>
        Probar BFF y Usuarios
      </button>}
      {result && <p role={result.error ? 'alert' : 'status'}>{result.message}</p>}
      {result?.interaction && <button type="button" className="button button--primary session-controls__button" disabled={busy}
        onClick={() => authorizeApi(location.pathname + location.search + location.hash)}>
        Continuar con Microsoft
      </button>}
    </details>
  )
}
