import { useEffect, useState } from 'react'
import { Outlet, useLocation, matchPath } from 'react-router'
import { Menu, PanelLeftClose, PanelLeftOpen } from 'lucide-react'
import SessionControls, { SessionError } from '../auth/SessionControls.jsx'
import Button from '../components/ui/Button.jsx'
import Dialog from '../components/ui/Dialog.jsx'
import Navigation, { Brand } from '../components/layout/Navigation.jsx'
import { navigationItems } from '../components/layout/navigationItems.js'
import { ROUTE_PATHS } from '../routes/routePaths.js'

export default function MainLayout() {
  const [compact, setCompact] = useState(false)
  const [menuDestination, setMenuDestination] = useState(null)
  const location = useLocation()
  const destination = location.pathname + location.search + location.hash
  if (menuDestination !== null && menuDestination !== destination) setMenuDestination(null)
  const menuOpen = menuDestination === destination
  const setMenuOpen = open => setMenuDestination(open ? destination : null)
  const title = navigationItems.find(item => item.path === location.pathname)?.label
    || (matchPath(ROUTE_PATHS.pago, location.pathname) ? 'Pago' : matchPath(ROUTE_PATHS.pedidoDetalle, location.pathname) ? 'Detalle del pedido'
      : location.pathname === ROUTE_PATHS.confirmarPedido ? 'Confirmar pedido'
        : location.pathname === ROUTE_PATHS.restaurantePedidos ? 'Pedidos del restaurante' : 'Pedidos360')

  useEffect(() => {
    const desktop = window.matchMedia('(min-width: 1024px)')
    const closeOnDesktop = event => { if (event.matches) setMenuDestination(null) }
    desktop.addEventListener('change', closeOnDesktop)
    return () => desktop.removeEventListener('change', closeOnDesktop)
  }, [])

  return <div className={`workspace ${compact ? 'workspace--compact' : ''}`}>
    <a className="skip-link" href="#contenido-principal">Saltar al contenido</a>
    <aside className="workspace-sidebar" aria-label="Menú de Pedidos360">
      <Brand compact={compact} />
      {!compact && <p className="workspace-sidebar__label">Tu espacio</p>}
      <Navigation compact={compact} />
      <div className="workspace-sidebar__footer">
        {!compact && <p>Elige, pide y disfruta.</p>}
        <Button variant="ghost" size={compact ? 'icon' : 'normal'} aria-label={compact ? 'Expandir menú' : 'Contraer menú'}
          aria-expanded={!compact} onClick={() => setCompact(value => !value)}>
          {compact ? <PanelLeftOpen size={20} aria-hidden="true" /> : <><PanelLeftClose size={20} aria-hidden="true" />Contraer menú</>}
        </Button>
      </div>
    </aside>
    <div className="workspace-body">
      <header className="workspace-header">
        <div className="workspace-header__page">
          <div className="workspace-mobile-menu">
            <Dialog open={menuOpen} onOpenChange={setMenuOpen} title="Pedidos360" description="Elige una sección para continuar."
              className="workspace-drawer" trigger={<Button variant="ghost" size="icon" aria-label="Abrir menú de navegación" aria-expanded={menuOpen}><Menu size={22} aria-hidden="true" /></Button>}>
              <Navigation onNavigate={event => {
                if (event.currentTarget.pathname === location.pathname) setMenuOpen(false)
              }} />
            </Dialog>
          </div>
          <span className="workspace-header__title">{title}</span>
        </div>
        <SessionControls />
      </header>
      <main id="contenido-principal" className="workspace-main" tabIndex={-1}>
        <SessionError />
        <Outlet />
      </main>
      <footer className="workspace-footer"><span>Pedidos360 Delivery</span><span>Proyecto académico</span></footer>
    </div>
  </div>
}
