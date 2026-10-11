import { Route, Routes } from 'react-router'
import { lazy, Suspense } from 'react'
import LoadingState from '../components/feedback/LoadingState.jsx'
import MainLayout from '../layouts/MainLayout.jsx'
import HomePage from '../pages/HomePage.jsx'
import NotFoundPage from '../pages/NotFoundPage.jsx'
import AccountPage from '../pages/AccountPage.jsx'
import CartPage from '../features/carrito/CartPage.jsx'
import RestaurantesPage from '../features/restaurantes/RestaurantesPage.jsx'
import ConfirmarPedidoPage from '../features/pedidos/ConfirmarPedidoPage.jsx'
import MisPedidosPage from '../features/pedidos/MisPedidosPage.jsx'
import PedidoDetallePage from '../features/pedidos/PedidoDetallePage.jsx'
import RestaurantePedidosPage from '../features/pedidos/RestaurantePedidosPage.jsx'
import PagoPage from '../features/pagos/PagoPage.jsx'
import RequireSession from '../auth/RequireSession.jsx'
import { ROUTE_PATHS } from './routePaths.js'
import { RequireAdmin } from '../features/admin/AdminAccess.jsx'
const CatalogAdminPage = lazy(() => import('../features/admin/CatalogAdminPage.jsx'))

function AppRouter() {
  return (
    <Routes>
      <Route element={<MainLayout />}>
        <Route path={ROUTE_PATHS.home} element={<HomePage />} />
        <Route element={<RequireSession />}>
          <Route element={<RequireAdmin />}>
            <Route path={ROUTE_PATHS.adminRestaurantes} element={<Suspense fallback={<LoadingState label="Preparando administración…" />}><CatalogAdminPage kind="restaurantes" /></Suspense>} />
            <Route path={ROUTE_PATHS.adminProductos} element={<Suspense fallback={<LoadingState label="Preparando administración…" />}><CatalogAdminPage kind="productos" /></Suspense>} />
          </Route>
          <Route path={ROUTE_PATHS.account} element={<AccountPage />} />
          <Route path={ROUTE_PATHS.restaurantes} element={<RestaurantesPage />} />
          <Route path={ROUTE_PATHS.cart} element={<CartPage />} />
          <Route path={ROUTE_PATHS.confirmarPedido} element={<ConfirmarPedidoPage />} />
          <Route path={ROUTE_PATHS.misPedidos} element={<MisPedidosPage />} />
          <Route path={ROUTE_PATHS.pedidoDetalle} element={<PedidoDetallePage />} />
          <Route path={ROUTE_PATHS.pago} element={<PagoPage />} />
          <Route path={ROUTE_PATHS.restaurantePedidos} element={<RestaurantePedidosPage />} />
        </Route>
        <Route path={ROUTE_PATHS.notFound} element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

export default AppRouter
