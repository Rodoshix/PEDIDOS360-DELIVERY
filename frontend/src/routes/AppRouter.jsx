import { Link, Route, Routes } from 'react-router'
import { ROUTE_PATHS } from './routePaths.js'

function HomePlaceholder() {
  return (
    <main>
      <h1>Pedidos360 Delivery</h1>
      <p>Aplicación base en construcción.</p>
    </main>
  )
}

function NotFoundPlaceholder() {
  return (
    <main>
      <h1>Página no encontrada</h1>
      <Link to={ROUTE_PATHS.home}>Volver al inicio</Link>
    </main>
  )
}

function AppRouter() {
  return (
    <Routes>
      <Route path={ROUTE_PATHS.home} element={<HomePlaceholder />} />
      <Route path={ROUTE_PATHS.notFound} element={<NotFoundPlaceholder />} />
    </Routes>
  )
}

export default AppRouter
