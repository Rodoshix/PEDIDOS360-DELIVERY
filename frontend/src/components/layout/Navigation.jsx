import { NavLink } from 'react-router'
import { UtensilsCrossed } from 'lucide-react'
import { ROUTE_PATHS } from '../../routes/routePaths.js'
import { navigationItems } from './navigationItems.js'
export function Brand({ onNavigate, compact = false }) {
  return <NavLink className="workspace-brand" to={ROUTE_PATHS.home} aria-label="Pedidos360, ir al inicio" onClick={onNavigate}>
    <span className="workspace-brand__mark"><UtensilsCrossed size={21} aria-hidden="true" /></span>
    {!compact && <span>Pedidos<span className="workspace-brand__accent">360</span></span>}
  </NavLink>
}
export default function Navigation({ compact = false, onNavigate }) {
  return <nav className="workspace-nav" aria-label="Navegación principal">
    {navigationItems.map(({ path, label, icon: Icon }) => <NavLink key={path} to={path} end={path === ROUTE_PATHS.home}
      aria-label={compact ? label : undefined} title={compact ? label : undefined} onClick={onNavigate}
      className={({ isActive }) => `workspace-nav__link ${isActive ? 'workspace-nav__link--active' : ''}`}>
      <Icon size={20} aria-hidden="true" />{!compact && <span>{label}</span>}
    </NavLink>)}
  </nav>
}
