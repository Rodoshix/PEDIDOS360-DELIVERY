import { useAuthSession } from '../auth/useAuthSession.js'

export default function AccountPage() {
  const { account } = useAuthSession()
  return (
    <section className="container account-section">
      <p className="eyebrow">Sesión iniciada</p>
      <h1>Mi cuenta</h1>
      <p>Hola, {account.name || account.username || 'usuario'}.</p>
      <p>Ya puedes acceder a esta página privada.</p>
      <p className="account-note">La gestión de tu perfil se incorporará más adelante. Esta vista todavía no consulta datos del backend.</p>
    </section>
  )
}
