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
import { ApiAccessError } from './auth/ApiAccessError.js'

const root = createRoot(document.getElementById('root'))
root.render(<AuthStartupStatus />)

async function bootstrap() {
  try {
    const { configureApiAuthentication } = await import('./services/httpClient.js')
    const { instance, tenantId, initialError, returnDestinationStore, apiTokenProvider, apiTokenRequest } = await initializeMsal()
    configureApiAuthentication(apiTokenProvider)
    root.render(
      <StrictMode>
        <MsalProvider instance={instance}>
          <AuthSessionProvider tenantId={tenantId} initialError={initialError} returnDestinationStore={returnDestinationStore}
            apiTokenProvider={apiTokenProvider} apiTokenRequest={apiTokenRequest}>
            <BrowserRouter>
              <App />
            </BrowserRouter>
          </AuthSessionProvider>
        </MsalProvider>
      </StrictMode>,
    )
  } catch (error) {
    const message = error instanceof AuthConfigurationError || error instanceof ApiAccessError
      ? error.message
      : 'No se pudo inicializar Microsoft Entra ID. Comprueba el navegador y vuelve a cargar la página.'
    root.render(<AuthStartupStatus error={message} />)
  }
}

void bootstrap()
