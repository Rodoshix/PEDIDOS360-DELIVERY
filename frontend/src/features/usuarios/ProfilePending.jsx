// Estado real hasta integrar Usuarios. No importa simuladores ni deduce un perfil de MSAL.
export default function ProfilePending() {
  return (
    <section className="profile-card" aria-labelledby="profile-heading">
      <p className="profile-badge">Integración pendiente</p>
      <h2 id="profile-heading">Perfil de Pedidos360</h2>
      <p className="account-note">Tus datos de contacto para la aplicación, separados de tu acceso con Microsoft.</p>
      <div role="status" className="profile-notice">
        Todavía no consultamos Usuarios. No sabemos si ya tienes un perfil registrado.
      </div>
      <div className="profile-empty">
        <h3>Perfil aún no consultado</h3>
        <p>Cuando conectemos el servicio, aquí aparecerán tu nombre, apellido, email de contacto y teléfono.</p>
      </div>
    </section>
  )
}
