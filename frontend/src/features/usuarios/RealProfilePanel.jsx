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
  const [editing, setEditing] = useState(false)
  const [message, setMessage] = useState('')
  const heading = useRef(null)
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
  const saving = state.status === 'saving'
  const queryFailed = state.status === 'error' && state.operation === 'load'
  const showForm = formVisible && (state.profile ? editing : state.status === 'empty' || saving || state.operation === 'save')

  async function save(draft) {
    setMessage('')
    if (await controller.save(draft)) {
      setEditing(false)
      setFormVisible(false)
      setMessage('Perfil guardado en Usuarios.')
      heading.current?.focus()
    }
  }

  function cancel() {
    controller.clearError()
    setEditing(false)
    setFormVisible(false)
    setMessage('Borrador descartado. No se enviaron cambios adicionales.')
    heading.current?.focus()
  }

  return (
    <section className="profile-card" aria-labelledby="profile-heading" aria-busy={loading || saving}>
      <p className="profile-badge">Usuarios · API real</p>
      <h2 ref={heading} tabIndex={-1} id="profile-heading">Perfil de Pedidos360</h2>
      <p className="account-note">Consulta autenticada mediante el BFF. No se utilizan perfiles de ejemplo.</p>
      {loading ? <p role="status">Consultando tu perfil…</p> : queryFailed ? (
        <div ref={errorHeading} tabIndex={-1} role="alert" className="profile-form__error-summary">
          <p>{state.error.message}</p>
          {state.error.code === 'INTERACTION_REQUIRED'
            ? <button type="button" className="button button--primary" onClick={() => authorizeApi(destination)}>Autorizar consulta con Microsoft</button>
            : state.error.code === 'UNAUTHORIZED'
              ? <button type="button" className="button button--primary" onClick={() => login(destination)}>Volver a iniciar sesión</button>
              : <button type="button" className="button button--secondary" onClick={() => controller.load()}>Reintentar consulta</button>}
        </div>
      ) : showForm ? (
        <>
          {!state.profile && <p role="status" className="profile-notice">{state.operation === 'save' && state.error ? 'No se pudo confirmar el estado del perfil tras el intento de guardado.' : 'La última consulta no encontró un perfil registrado en Usuarios.'}</p>}
          <Suspense fallback={<p role="status">Preparando formulario…</p>}>
            <ProfileForm mode="real" initialProfile={state.profile} saving={saving} onApply={save} onCancel={cancel}
              submitError={state.operation === 'save' ? state.error : null} />
          </Suspense>
          {state.error?.code === 'INTERACTION_REQUIRED' && <button type="button" className="button button--secondary" onClick={() => authorizeApi(destination)}>Autorizar guardado con Microsoft</button>}
          {state.error?.code === 'UNAUTHORIZED' && <button type="button" className="button button--secondary" onClick={() => login(destination)}>Volver a iniciar sesión</button>}
        </>
      ) : state.profile ? (
        <>
          <ProfileDetails profile={state.profile} />
          {state.profile.activo && <button type="button" className="button button--primary" onClick={() => { setMessage(''); setFormVisible(true); setEditing(true) }}>Editar perfil</button>}
        </>
      ) : state.status === 'empty' ? (
        <>
          <p role="status" className="profile-notice">Todavía no tienes un perfil registrado en Usuarios.</p>
          <button type="button" className="button button--secondary" onClick={() => { setMessage(''); setFormVisible(true) }}>Mostrar formulario de perfil</button>
        </>
      ) : null}
      {message && <p role="status" className="profile-notice">{message}</p>}
      {!loading && !saving && !showForm && !queryFailed && <button type="button" className="button button--secondary" onClick={() => { setMessage(''); void controller.load() }}>Actualizar consulta</button>}
      <p className="account-note">Tras un error de guardado, conserva una copia de tu borrador y consulta el estado actual antes de repetir. Autorizar con Microsoft puede requerir salir de esta vista.</p>
    </section>
  )
}
