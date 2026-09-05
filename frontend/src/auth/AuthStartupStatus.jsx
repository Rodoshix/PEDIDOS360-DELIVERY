function AuthStartupStatus({ error }) {
  return (
    <main className="container auth-startup" aria-labelledby="auth-startup-title">
      <h1 id="auth-startup-title">{error ? 'No pudimos preparar el acceso' : 'Preparando Pedidos360'}</h1>
      <p role={error ? 'alert' : 'status'}>
        {error || 'Inicializando la configuración de Microsoft Entra ID…'}
      </p>
      {error && (
        <p>
          Revisa las variables VITE_ENTRA_* en .env.local y reinicia Vite.
          En un despliegue, corrige las variables y vuelve a generar el build.
          No se ha habilitado una sesión de prueba.
        </p>
      )}
    </main>
  )
}

export default AuthStartupStatus
