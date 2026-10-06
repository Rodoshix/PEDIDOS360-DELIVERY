import { House, Store, ShoppingBag, ReceiptText, CircleUserRound } from 'lucide-react'
import { ROUTE_PATHS } from '../../routes/routePaths.js'

export const navigationItems = [
  { path: ROUTE_PATHS.home, label: 'Inicio', icon: House },
  { path: ROUTE_PATHS.restaurantes, label: 'Restaurantes', icon: Store },
  { path: ROUTE_PATHS.cart, label: 'Carrito', icon: ShoppingBag },
  { path: ROUTE_PATHS.misPedidos, label: 'Mis pedidos', icon: ReceiptText },
  { path: ROUTE_PATHS.account, label: 'Mi cuenta', icon: CircleUserRound },
  { path: ROUTE_PATHS.adminRestaurantes, label: 'Administrar restaurantes', icon: Store, admin: true },
  { path: ROUTE_PATHS.adminProductos, label: 'Administrar productos', icon: ShoppingBag, admin: true },
]
