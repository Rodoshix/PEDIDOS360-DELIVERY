import { Link } from 'react-router'
import { ArrowUpRight, Store, ShoppingBag, ReceiptText } from 'lucide-react'
import { ROUTE_PATHS } from '../routes/routePaths.js'
import '../styles/home.css'

export default function HomePage() {
  return <div className="container home-page">
    <section className="home-welcome" aria-labelledby="home-title">
      <div className="home-welcome__copy">
        <h1 id="home-title">Tu pedido, simple y en un solo lugar.</h1>
        <p>Elige un restaurante, arma tu carrito y revisa tus pedidos desde tu espacio en Pedidos360.</p>
        <Link className="button button--primary" to={ROUTE_PATHS.restaurantes}>Explorar restaurantes <ArrowUpRight size={18} aria-hidden="true" /></Link>
      </div>
      <div className="home-welcome__aside">
        <ShoppingBag size={28} aria-hidden="true" />
        <h2>Todo empieza con una buena elección.</h2>
        <p>Encuentra lo que quieres pedir y confirma los detalles antes de continuar.</p>
      </div>
    </section>
    <section className="home-shortcuts" aria-labelledby="home-shortcuts-title">
      <div className="home-section-heading"><h2 id="home-shortcuts-title">¿Por dónde seguimos?</h2><p>Acceso directo a lo que necesitas.</p></div>
      <div className="home-shortcut-list">
        <Link to={ROUTE_PATHS.restaurantes} className="home-shortcut"><Store size={22} aria-hidden="true" /><div><h3>Restaurantes</h3><p>Consulta el catálogo y elige tus productos.</p></div><ArrowUpRight size={20} aria-hidden="true" /></Link>
        <Link to={ROUTE_PATHS.cart} className="home-shortcut"><ShoppingBag size={22} aria-hidden="true" /><div><h3>Mi carrito</h3><p>Revisa cantidades e importes antes de confirmar.</p></div><ArrowUpRight size={20} aria-hidden="true" /></Link>
        <Link to={ROUTE_PATHS.misPedidos} className="home-shortcut"><ReceiptText size={22} aria-hidden="true" /><div><h3>Mis pedidos</h3><p>Consulta tu historial y el estado de cada pedido.</p></div><ArrowUpRight size={20} aria-hidden="true" /></Link>
      </div>
    </section>
    <section className="home-account-note" aria-labelledby="home-account-title"><h2 id="home-account-title">Tu cuenta, tu espacio.</h2><p>Entra con Microsoft y mantén tus datos de contacto en Mi cuenta. Tu perfil de Pedidos360 y tu identidad Microsoft se gestionan por separado.</p><Link to={ROUTE_PATHS.account}>Ir a mi cuenta <ArrowUpRight size={16} aria-hidden="true" /></Link></section>
  </div>
}
