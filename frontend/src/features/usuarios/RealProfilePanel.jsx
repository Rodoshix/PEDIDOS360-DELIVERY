import { lazy, Suspense, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useLocation } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import { createProfileController, ProfileError } from './profileService.js'
import { createProfileHttpAdapter } from './profileHttpAdapter.js'
import ProfileDetails from './ProfileDetails.jsx'
const ProfileForm = lazy(() => import('./ProfileForm.jsx'))

export default function RealProfilePanel() {
  const [controller] = useState(createProfileController)
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot)
  const { busy: sessionBusy, authorizeApi, login } = useAuthSession()
  const location = useLocation()
  const errorHeading = useRef(null)
  const [formVisible, setFormVisible] = useState(true)
  const destination = location.pathname + location.search + location.hash

  useEffect(() => {
    let disposed = false
    controller.connect(null)
    if (!sessionBusy) {
      import('../../services/httpClient.js').then(({ default: client }) => {
        if (disposed) return
        controller.connect(createProfileHttpAdapter(client))
        void controller.load()
      }).catch(() => {
        if (disposed) return
        controller.connect({ read: async () => { throw new ProfileError('LOAD_FAILED') } })
        void controller.load()
      })
    }
    return () => { disposed = true; controller.cancelPending() }
  }, [controller, sessionBusy])

  useEffect(() => { if (state.error) errorHeading.current?.focus() }, [state.error])

  const loading = sessionBusy || ['idle', 'loading'].includes(state.status)
  return (
    <section className="profile-card" aria-labelledby="profile-heading" aria-busy={loading}>
      <p className="profile-badge">Usuarios · API real</p>
      <h2 id="profile-heading">Perfil de Pedidos360</h2>
      <p className="account-note">Consulta autenticada mediante el BFF. No se utilizan perfiles de ejemplo.</p>
      {loading ? <p role="status">Consultando tu perfil…</p> : state.status === 'error' ? (
        <div ref={errorHeading} tabIndex={-1} role="alert" className="profile-form__error-summary">
          <p>{state.error.message}</p>
          {state.error.code === 'INTERACTION_REQUIRED'
            ? <button type="button" className="button button--primary" onClick={() => authorizeApi(destination)}>Autorizar consulta con Microsoft</button>
            : state.error.code === 'UNAUTHORIZED'
              ? <button type="button" className="button button--primary" onClick={() => login(destination)}>Volver a iniciar sesión</button>
              : <button type="button" className="button button--secondary" onClick={() => controller.load()}>Reintentar consulta</button>}
        </div>
      ) : state.status === 'ready' ? <ProfileDetails profile={state.profile} /> : state.status === 'empty' ? (
        <>
          <p role="status" className="profile-notice">Todavía no tienes un perfil registrado en Usuarios.</p>
          {formVisible
            ? <Suspense fallback={<p role="status">Preparando formulario…</p>}><ProfileForm mode="real" saveEnabled={false} onCancel={() => setFormVisible(false)} /></Suspense>
            : <button type="button" className="button button--secondary" onClick={() => setFormVisible(true)}>Mostrar formulario de perfil</button>}
        </>
      ) : null}
      <p className="account-note">Bloque 1: solo consulta. Crear y editar en Usuarios se habilitará en el siguiente bloque.</p>
    </section>
  )
}
