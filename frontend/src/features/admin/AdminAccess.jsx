import { useEffect, useState } from 'react'
import { Outlet, useLocation } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import { adminFailure } from './catalogAdminHttpAdapter.js'
import LoadingState from '../../components/feedback/LoadingState.jsx'
import { Alert } from '../../components/feedback/Feedback.jsx'
import Button from '../../components/ui/Button.jsx'

import { AdminAccessContext as Context, useAdminAccess } from './useAdminAccess.js'
export function AdminAccessProvider({ children }) {
  const { account, busy } = useAuthSession()
  const key = JSON.stringify(account && [account.tenantId, account.homeAccountId, account.localAccountId])
  const [result, setResult] = useState(null), [attempt, setAttempt] = useState(0)
  useEffect(() => {
    if (!account || busy) return
    const abort = new AbortController()
    import('../../services/httpClient.js').then(async ({ default: client }) => {
      try {
        const response = await client.get('/restaurantes/admin/acceso', { signal: abort.signal })
        if (!abort.signal.aborted) setResult({ key, attempt, status: response.status === 204 ? 'allowed' : 'error' })
      } catch (error) {
        if (!abort.signal.aborted) { const failure = adminFailure(error); setResult({ key, attempt, status: failure.code === 'FORBIDDEN' ? 'denied' : 'error', error: failure }) }
      }
    }).catch(() => { if (!abort.signal.aborted) setResult({ key, attempt, status: 'error' }) })
    return () => abort.abort()
  }, [key, busy, attempt, account])
  const value = !account ? { status: 'denied' } : busy || result?.key !== key || result?.attempt !== attempt ? { status: 'loading' } : result
  return <Context.Provider value={{ ...value, retry: () => setAttempt(value => value + 1) }}>{children}</Context.Provider>
}
export function RequireAdmin() {
  const access = useAdminAccess(), session = useAuthSession(), location = useLocation()
  const destination = location.pathname + location.search + location.hash
  if (access.status === 'allowed') return <Outlet />
  if (access.status === 'loading') return <LoadingState label="Comprobando permiso de administración…" />
  return <section className="container cart-section"><h1>{access.status === 'denied' ? 'Acceso de administración requerido' : 'No pudimos comprobar el acceso'}</h1>
    <Alert>{access.status === 'denied' ? 'Tu cuenta no tiene permiso ADMIN para administrar el catálogo.' : access.error?.message || 'Puedes volver a comprobar el permiso.'}</Alert>
    {access.status !== 'denied' && <div className="cart-actions"><Button onClick={access.retry}>Reintentar comprobación</Button>
      {access.error?.code === 'INTERACTION_REQUIRED' && <Button onClick={() => session.authorizeApi(destination)}>Continuar con Microsoft</Button>}
      {access.error?.code === 'UNAUTHORIZED' && <Button onClick={() => session.login(destination)}>Iniciar sesión con Microsoft</Button>}</div>}
  </section>
}
