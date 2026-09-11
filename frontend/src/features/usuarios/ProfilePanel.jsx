import { lazy, Suspense, useEffect, useRef, useState, useSyncExternalStore } from 'react'
import ProfileDetails from './ProfileDetails.jsx'
import { createProfileController } from './profileService.js'
import { createProfileDemoAdapter, PROFILE_SCENARIOS } from './profileDemoAdapter.js'

const ProfileForm = lazy(() => import('./ProfileForm.jsx'))

export default function ProfilePanel({ demoEnabled = false }) {
  const [controller] = useState(createProfileController)
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot, controller.getSnapshot)
  const [editing, setEditing] = useState(false)
  const [scenario, setScenario] = useState('example')
  const [message, setMessage] = useState('')
  const heading = useRef(null)
  const loadError = useRef(null)
  const returnFocus = useRef(false)
  const profile = demoEnabled ? state.profile : null
  const busy = state.status === 'loading' || state.status === 'saving'
  const queryFailed = state.status === 'error' && state.operation === 'load'

  useEffect(() => () => controller.cancelPending(), [controller])
  useEffect(() => {
    if (!editing && returnFocus.current) {
      heading.current?.focus()
      returnFocus.current = false
    }
  }, [editing])
  useEffect(() => { if (queryFailed) loadError.current?.focus() }, [queryFailed, state.error])

  function finishEditing() {
    returnFocus.current = true
    setEditing(false)
  }

  async function startScenario(value, create = false) {
    if (busy || editing) return
    setMessage('')
    setScenario(value)
    controller.connect(createProfileDemoAdapter({ scenario: value }))
    const loaded = await controller.load()
    if (loaded && create) setEditing(true)
  }

  async function applyExample(payload) {
    if (await controller.save(payload)) {
      setMessage('Guardado simulado completado. Los cambios están solo en memoria; no se guardaron en Usuarios.')
      finishEditing()
    }
  }

  return (
    <section className="profile-card" aria-labelledby="profile-heading">
      <p className="profile-badge">{demoEnabled && state.status !== 'idle' ? 'Datos de prueba' : 'Integración pendiente'}</p>
      <h2 ref={heading} tabIndex={-1} id="profile-heading">Perfil de Pedidos360</h2>
      <p className="account-note">Tus datos de contacto para la aplicación, separados de tu acceso con Microsoft.</p>
      <div role="status" className="profile-notice">
        {demoEnabled && state.status !== 'idle'
          ? 'Simulación local: datos de perfil ficticios. No se consulta ni se guarda en Usuarios.'
          : 'Todavía no consultamos Usuarios. No sabemos si ya tienes un perfil registrado.'}
      </div>
      {demoEnabled && state.status === 'loading' && <p role="status">Consultando perfil de prueba…</p>}
      {demoEnabled && queryFailed && (
        <div ref={loadError} tabIndex={-1} role="alert" className="profile-form__error-summary">
          <p>{state.error.message}</p>
          <button type="button" className="button button--secondary session-controls__button" onClick={() => controller.load()}>Reintentar consulta</button>
        </div>
      )}
      {demoEnabled && editing ? (
        <Suspense fallback={<p role="status">Preparando formulario…</p>}>
          <ProfileForm initialProfile={profile} onApply={applyExample} saving={state.status === 'saving'}
            submitError={state.operation === 'save' ? state.error : null} onCancel={() => {
              controller.clearError()
              setMessage('Borrador descartado. No se modificaron los datos del ejemplo.')
              finishEditing()
            }} />
        </Suspense>
      ) : profile && !queryFailed && !busy ? (
        <>
          <ProfileDetails profile={profile} />
          {!profile.activo && <p className="profile-notice">Perfil de prueba inactivo: la edición está deshabilitada.</p>}
        </>
      ) : !busy && !queryFailed && (
        <div className="profile-empty">
          <h3>{demoEnabled && state.status === 'empty' ? 'Sin perfil en este escenario' : 'Perfil aún no consultado'}</h3>
          <p>{demoEnabled && state.status === 'empty'
            ? 'La consulta simulada no encontró un perfil. Puedes probar la creación sin registrar un usuario real.'
            : 'Cuando conectemos el servicio, aquí aparecerán tu nombre, apellido, email de contacto y teléfono.'}</p>
        </div>
      )}
      {message && <p role="status" className="profile-notice">{message}</p>}
      {demoEnabled && !editing && (
        <div className="profile-demo">
          <p className="account-note">Solo datos ficticios. Las consultas y guardados tienen una demora simulada.</p>
          <div className="account-actions">
            {state.status === 'idle' && <>
              <button type="button" className="button button--primary session-controls__button" onClick={() => startScenario('empty', true)}>Probar creación de perfil</button>
              <button type="button" className="button button--secondary session-controls__button" onClick={() => startScenario('example')}>Ver perfil de ejemplo</button>
            </>}
            {!busy && !queryFailed && (profile ? profile.activo : state.status === 'empty' || state.operation === 'save') && (
              <button type="button" className="button button--primary session-controls__button" onClick={() => { setMessage(''); setEditing(true) }}>
                {profile ? 'Editar ejemplo' : 'Probar creación de perfil'}
              </button>
            )}
            {state.status !== 'idle' && <button type="button" className="button button--secondary session-controls__button" disabled={busy} onClick={() => {
              controller.connect(null)
              setMessage('')
            }}>Quitar ejemplo</button>}
          </div>
          <label className="profile-scenario" htmlFor="profile-scenario">Escenario de prueba
            <select id="profile-scenario" value={scenario} disabled={busy} onChange={event => setScenario(event.target.value)}>
              {PROFILE_SCENARIOS.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}
            </select>
          </label>
          <button type="button" className="button button--secondary session-controls__button" disabled={busy} onClick={() => startScenario(scenario)}>Cargar escenario</button>
        </div>
      )}
    </section>
  )
}
