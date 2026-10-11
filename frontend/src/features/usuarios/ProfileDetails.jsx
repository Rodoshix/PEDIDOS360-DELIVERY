export default function ProfileDetails({ profile }) {
  return (
    <dl className="profile-data profile-data--columns">
      <div><dt>Nombre</dt><dd>{profile.nombre}</dd></div>
      <div><dt>Apellido</dt><dd>{profile.apellido}</dd></div>
      <div><dt>Email de contacto</dt><dd>{profile.email}</dd></div>
      <div><dt>Teléfono</dt><dd>{profile.telefono || 'Sin registrar'}</dd></div>
      <div><dt>Estado del perfil</dt><dd><Badge tone={profile.activo ? 'success' : 'neutral'}>{profile.activo ? 'Activo' : 'Inactivo'}</Badge></dd></div>
    </dl>
  )
}
import Badge from '../../components/ui/Badge.jsx'
