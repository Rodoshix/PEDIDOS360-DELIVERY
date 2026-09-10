import { useState } from 'react'
import ProfileDetails from './ProfileDetails.jsx'
import { createExampleProfile } from './profileDemo.js'

export default function ProfilePanel({ demoEnabled = false }) {
  const [example, setExample] = useState(null)
  const profile = demoEnabled ? example : null

  return (
    <section className="profile-card" aria-labelledby="profile-heading">
      <p className="profile-badge">{profile ? 'Datos de ejemplo' : 'Integración pendiente'}</p>
      <h2 id="profile-heading">Perfil de Pedidos360</h2>
      <p className="account-note">Tus datos de contacto para la aplicación, separados de tu acceso con Microsoft.</p>
      <div role="status" className="profile-notice">
        {profile
          ? 'Estás viendo un perfil ficticio. No pertenece a tu cuenta y no se ha guardado en ningún servicio.'
          : 'Todavía no consultamos Usuarios. No sabemos si ya tienes un perfil registrado.'}
      </div>
      {profile ? <ProfileDetails profile={profile} /> : (
        <div className="profile-empty">
          <h3>Perfil aún no consultado</h3>
          <p>Cuando conectemos el servicio, aquí aparecerán tu nombre, apellido, email de contacto y teléfono.</p>
        </div>
      )}
      {demoEnabled && (
        <div className="profile-demo">
          <p className="account-note">Vista de desarrollo: puedes explorar un ejemplo sin cargar datos reales.</p>
          <button type="button" className="button button--primary session-controls__button"
            onClick={() => setExample(profile ? null : createExampleProfile())}>
            {profile ? 'Quitar ejemplo' : 'Ver perfil de ejemplo'}
          </button>
        </div>
      )}
    </section>
  )
}
