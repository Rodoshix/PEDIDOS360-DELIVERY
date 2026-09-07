import { NavLink, Outlet } from 'react-router'
import { ROUTE_PATHS } from '../routes/routePaths.js'
import SessionControls, { SessionError } from '../auth/SessionControls.jsx'

function MainLayout() {
  return (
    <div className="app-shell">
      <a className="skip-link" href="#contenido-principal">
        Saltar al contenido
      </a>

      <header className="site-header">
        <div className="container site-header__content">
          <NavLink className="brand" to={ROUTE_PATHS.home} aria-label="Pedidos360, ir al inicio">
            <span className="brand__mark" aria-hidden="true">
              P360
            </span>
            <span>Pedidos360</span>
          </NavLink>

          <nav aria-label="Navegación principal">
            <NavLink
              className={({ isActive }) =>
                isActive ? 'nav-link nav-link--active' : 'nav-link'
              }
              end
              to={ROUTE_PATHS.home}
            >
              Inicio
            </NavLink>
            <NavLink
              className={({ isActive }) => isActive ? 'nav-link nav-link--active' : 'nav-link'}
              to={ROUTE_PATHS.account}
            >
              Mi cuenta
            </NavLink>
          </nav>

          <SessionControls />
        </div>
      </header>

      <main id="contenido-principal" className="site-main">
        <SessionError />
        <Outlet />
      </main>

      <footer className="site-footer">
        <div className="container site-footer__content">
          <span>Pedidos360 Delivery</span>
          <span>Proyecto académico</span>
        </div>
      </footer>
    </div>
  )
}

export default MainLayout
