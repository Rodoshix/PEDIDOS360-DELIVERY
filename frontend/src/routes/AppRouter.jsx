import { Route, Routes } from 'react-router'
import MainLayout from '../layouts/MainLayout.jsx'
import HomePage from '../pages/HomePage.jsx'
import NotFoundPage from '../pages/NotFoundPage.jsx'
import AccountPage from '../pages/AccountPage.jsx'
import RequireSession from '../auth/RequireSession.jsx'
import { ROUTE_PATHS } from './routePaths.js'

function AppRouter() {
  return (
    <Routes>
      <Route element={<MainLayout />}>
        <Route path={ROUTE_PATHS.home} element={<HomePage />} />
        <Route element={<RequireSession />}>
          <Route path={ROUTE_PATHS.account} element={<AccountPage />} />
        </Route>
        <Route path={ROUTE_PATHS.notFound} element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

export default AppRouter
