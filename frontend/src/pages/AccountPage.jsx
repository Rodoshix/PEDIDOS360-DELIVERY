import { useAuthSession } from '../auth/useAuthSession.js'
import { useState } from 'react'
import { useLocation } from 'react-router'
import { ApiAccessError } from '../auth/ApiAccessError.js'

export default function AccountPage() {
  const { account, busy, checkApiAccess, authorizeApi } = useAuthSession()
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
  return (
    <section className="container account-section">
      <p className="eyebrow">Sesión iniciada</p>
      <h1>Mi cuenta</h1>
      <p>Hola, {account.name || account.username || 'usuario'}.</p>
      <p>Ya puedes acceder a esta página privada.</p>
      <p className="account-note">La gestión de tu perfil se incorporará más adelante. Esta vista todavía no consulta datos del backend.</p>
      <button type="button" className="button button--primary session-controls__button" disabled={checking || busy} onClick={check}>
        {checking ? 'Comprobando permiso…' : 'Comprobar permiso de API'}
      </button>
      {result && <p role={result.error ? 'alert' : 'status'}>{result.message}</p>}
      {result?.interaction && <button type="button" className="button button--primary session-controls__button" disabled={busy}
        onClick={() => authorizeApi(location.pathname + location.search + location.hash)}>
        Continuar con Microsoft
      </button>}
    </section>
  )
}
