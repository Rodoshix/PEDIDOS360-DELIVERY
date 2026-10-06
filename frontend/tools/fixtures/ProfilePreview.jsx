import { StrictMode, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import { AuthSessionContext } from '../../src/auth/useAuthSession.js'
import { ApiAccessError } from '../../src/auth/ApiAccessError.js'
import AppRouter from '../../src/routes/AppRouter.jsx'
import '../../src/styles/global.css'
import '../../src/styles/system.css'

let previewIdentity = 'A'
const mockApi = document.getElementById('root')?.dataset.mockApi === 'true'
if (mockApi) {
  const { configureApiAuthentication } = await import('../../src/services/httpClient.js')
  configureApiAuthentication({ getAccessToken: async () => `preview-only-${previewIdentity}` })
}

export default function ProfilePreview({ initialRoute = '/mi-cuenta?tab=datos#contacto' }) {
  const [identity, setIdentity] = useState('A')
  const [signedIn, setSignedIn] = useState(true)
  const session = {
    account: signedIn ? {
      tenantId: 'directorio-ficticio', homeAccountId: `cuenta-${identity}`, localAccountId: `objeto-${identity}`,
      name: `Cuenta de prueba ${identity}`, username: `cuenta-${identity.toLowerCase()}@example.test`,
    } : null,
    busy: false, pending: null, error: null,
    login: () => setSignedIn(true), logout: () => setSignedIn(false),
    checkApiAccess: async () => { throw new ApiAccessError('TOKEN_UNAVAILABLE') },
    authorizeApi: () => {},
  }
  return (
    <>
      <div className="container" style={{ paddingBlock: 16 }}>
        <p>Banco visual de pruebas: sesión ficticia, sin conexión a Microsoft ni al backend.</p>
        <button type="button" onClick={() => { previewIdentity = identity === 'A' ? 'B' : 'A'; setIdentity(previewIdentity) }}>Cambiar cuenta de prueba</button>
        {mockApi && <div className="preview-scenarios">{['normal', 'empty', 'error', 'slow'].map(scenario => <button key={scenario} type="button" onClick={async () => {
          await fetch('/__preview/scenario', { method: 'POST', body: scenario }); window.location.reload()
        }}>{scenario}</button>)}</div>}
      </div>
      <AuthSessionContext.Provider value={session}>
        <MemoryRouter initialEntries={[initialRoute]}><AppRouter /></MemoryRouter>
      </AuthSessionContext.Provider>
    </>
  )
}

const root = document.getElementById('root')
createRoot(root).render(<StrictMode><ProfilePreview initialRoute={root.dataset.previewRoute} /></StrictMode>)
