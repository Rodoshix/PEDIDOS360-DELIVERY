import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { MsalProvider } from '@azure/msal-react'
import { AuthConfigurationError } from './auth/authConfiguration.js'
import { initializeMsal } from './auth/msalClient.js'
import AuthStartupStatus from './auth/AuthStartupStatus.jsx'
import { AuthSessionProvider } from './auth/AuthSessionProvider.jsx'
import './styles/global.css'
import App from './App.jsx'

const root = createRoot(document.getElementById('root'))
root.render(<AuthStartupStatus />)

async function bootstrap() {
  try {
    const { instance, tenantId, initialError } = await initializeMsal()
    root.render(
      <StrictMode>
        <MsalProvider instance={instance}>
          <AuthSessionProvider tenantId={tenantId} initialError={initialError}>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </AuthSessionProvider>
        </MsalProvider>
      </StrictMode>,
    )
  } catch (error) {
    const message = error instanceof AuthConfigurationError
      ? error.message
      : 'No se pudo inicializar Microsoft Entra ID. Comprueba el navegador y vuelve a cargar la página.'
    root.render(<AuthStartupStatus error={message} />)
  }
}

void bootstrap()
