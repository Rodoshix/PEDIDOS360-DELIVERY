import { useAuthSession } from '../auth/useAuthSession.js'
import ApiPermissionCheck from '../features/usuarios/ApiPermissionCheck.jsx'
import ProfilePanel from '../features/usuarios/ProfilePanel.jsx'
import '../features/usuarios/profile.css'

export default function AccountPage() {
  const { account } = useAuthSession()
  // Una cuenta distinta no hereda el estado de la vista anterior.
  const profileKey = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId])
  return (
    <section className="container account-section">
      <p className="eyebrow">Tu espacio en Pedidos360</p>
      <h1>Mi cuenta</h1>
      <p className="account-note">Consulta tus datos de contacto y la cuenta con la que iniciaste sesión.</p>
      <div className="profile-layout">
        <ProfilePanel key={profileKey} demoEnabled={import.meta.env.DEV} />
        <aside className="profile-card profile-session" aria-labelledby="session-heading">
          <p className="profile-badge">Sesión Microsoft</p>
          <h2 id="session-heading">Tu acceso</h2>
          <dl className="profile-data">
            <div><dt>Nombre de la cuenta</dt><dd>{account.name || 'No informado por Microsoft'}</dd></div>
            <div><dt>Identificador de inicio de sesión</dt><dd>{account.username || 'No informado por Microsoft'}</dd></div>
          </dl>
          <p className="account-note">Estos datos identifican tu sesión. No son un perfil registrado en Pedidos360 ni se editan desde aquí.</p>
          <ApiPermissionCheck key={profileKey} />
        </aside>
      </div>
    </section>
  )
}
