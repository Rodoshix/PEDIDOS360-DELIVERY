import { lazy, Suspense, useEffect, useRef, useState } from 'react'
import ProfileDetails from './ProfileDetails.jsx'
import { createExampleProfile } from './profileDemo.js'

const ProfileForm = lazy(() => import('./ProfileForm.jsx'))

export default function ProfilePanel({ demoEnabled = false }) {
  const [example, setExample] = useState(null)
  const [editing, setEditing] = useState(false)
  const [message, setMessage] = useState('')
  const heading = useRef(null)
  const returnFocus = useRef(false)
  const profile = demoEnabled ? example : null

  useEffect(() => {
    if (!editing && returnFocus.current) {
      heading.current?.focus()
      returnFocus.current = false
    }
  }, [editing])

  function finishEditing() {
    returnFocus.current = true
    setEditing(false)
  }

  function applyExample(payload) {
    setExample({ ...(profile || createExampleProfile()), ...payload })
    setMessage('Cambios aplicados solo al ejemplo. No se guardaron en Usuarios.')
    finishEditing()
  }

  return (
    <section className="profile-card" aria-labelledby="profile-heading">
      <p className="profile-badge">{profile ? 'Datos de ejemplo' : 'Integración pendiente'}</p>
      <h2 ref={heading} tabIndex={-1} id="profile-heading">Perfil de Pedidos360</h2>
      <p className="account-note">Tus datos de contacto para la aplicación, separados de tu acceso con Microsoft.</p>
      <div role="status" className="profile-notice">
        {profile
          ? 'Estás viendo un perfil ficticio. No pertenece a tu cuenta y no se ha guardado en ningún servicio.'
          : 'Todavía no consultamos Usuarios. No sabemos si ya tienes un perfil registrado.'}
      </div>
      {demoEnabled && editing ? (
        <Suspense fallback={<p role="status">Preparando formulario…</p>}>
        <ProfileForm initialProfile={profile} onApply={applyExample} onCancel={() => {
          setMessage('Borrador descartado. No se modificaron los datos del ejemplo.')
          finishEditing()
        }} />
        </Suspense>
      ) : profile ? <ProfileDetails profile={profile} /> : (
        <div className="profile-empty">
          <h3>Perfil aún no consultado</h3>
          <p>Cuando conectemos el servicio, aquí aparecerán tu nombre, apellido, email de contacto y teléfono.</p>
        </div>
      )}
      {message && <p role="status" className="profile-notice">{message}</p>}
      {demoEnabled && !editing && (
        <div className="profile-demo">
          <p className="account-note">Vista de desarrollo: puedes explorar un ejemplo sin cargar datos reales.</p>
          <div className="account-actions">
          <button type="button" className="button button--primary session-controls__button" onClick={() => {
            setMessage('')
            setEditing(true)
          }}>{profile ? 'Editar ejemplo' : 'Probar creación de perfil'}</button>
          <button type="button" className="button button--secondary session-controls__button"
            onClick={() => { setMessage(''); setExample(profile ? null : createExampleProfile()) }}>
            {profile ? 'Quitar ejemplo' : 'Ver perfil de ejemplo'}
          </button>
          </div>
        </div>
      )}
    </section>
  )
}
