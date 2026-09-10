import { Link, Outlet, useLocation } from 'react-router'
import { useAuthSession } from './useAuthSession.js'
import { ROUTE_PATHS } from '../routes/routePaths.js'

export default function RequireSession() {
  const { account, busy, login } = useAuthSession()
  const location = useLocation()

  if (busy) return <section className="container account-section" role="status">Comprobando tu sesión…</section>
  if (account) return <Outlet />

  return (
    <section className="container account-section">
      <p className="eyebrow">Acceso a tu cuenta</p>
      <h1>Inicia sesión para continuar</h1>
      <p>Esta página requiere una sesión. Después de entrar con Microsoft volverás aquí.</p>
      <div className="account-actions">
        <button
          className="button button--primary session-controls__button"
          type="button"
          onClick={() => login(location.pathname + location.search + location.hash)}
        >
          Entrar para continuar
        </button>
        <Link to={ROUTE_PATHS.home}>Volver al inicio</Link>
      </div>
    </section>
  )
}
