import LoadingState from '../components/feedback/LoadingState.jsx'

function AuthStartupStatus({ error }) {
  return (
    <main className="container auth-startup" aria-labelledby="auth-startup-title">
      <h1 id="auth-startup-title">{error ? 'No pudimos preparar el acceso' : 'Preparando Pedidos360'}</h1>
      {error ? <p role="alert">{error}</p> : <LoadingState label="Inicializando la configuración de Microsoft Entra ID…" />}
      {error && (
        <details><summary>Información de configuración</summary><p>
          Revisa las variables VITE_ENTRA_* en .env.local y reinicia Vite.
          En un despliegue, corrige las variables y vuelve a generar el build.
          No se ha habilitado una sesión de prueba.
        </p></details>
      )}
    </main>
  )
}

export default AuthStartupStatus
