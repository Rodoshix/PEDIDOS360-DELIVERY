import { useAuthSession } from './useAuthSession.js'
import { useLocation } from 'react-router'

function SessionControls() {
  const { account, busy, pending, login, logout } = useAuthSession()
  const location = useLocation()

  return (
    <section className="session-controls" aria-label="Sesión de Microsoft" aria-busy={busy}>
      <div className="session-controls__identity" aria-live="polite">
        {busy ? (
          <span role="status">{pending === 'logout' ? 'Cerrando sesión…' : 'Preparando acceso…'}</span>
        ) : account ? (
          <>
            <strong>{account.name || account.username || 'Cuenta Microsoft'}</strong>
            {account.username && <span>{account.username}</span>}
          </>
        ) : <span>Sin sesión iniciada</span>}
      </div>
      <button
        type="button"
        className="button button--primary session-controls__button"
        disabled={busy}
        onClick={account ? logout : () => login(location.pathname + location.search + location.hash)}
      >
        {account ? 'Cerrar sesión' : 'Iniciar sesión con Microsoft'}
      </button>
    </section>
  )
}

export function SessionError() {
  const { error } = useAuthSession()
  return error ? <p className="container session-error" role="alert">{error}</p> : null
}

export default SessionControls
