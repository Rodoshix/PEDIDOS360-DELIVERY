import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { MemoryRouter } from 'react-router'
import { AuthSessionContext } from '../../src/auth/useAuthSession.js'
import { ApiAccessError } from '../../src/auth/ApiAccessError.js'
import AppRouter from '../../src/routes/AppRouter.jsx'
import '../../src/styles/global.css'

export default function ProfilePreview() {
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
        <button type="button" onClick={() => setIdentity(identity === 'A' ? 'B' : 'A')}>Cambiar cuenta de prueba</button>
      </div>
      <AuthSessionContext.Provider value={session}>
        <MemoryRouter initialEntries={['/mi-cuenta?tab=datos#contacto']}><AppRouter /></MemoryRouter>
      </AuthSessionContext.Provider>
    </>
  )
}

createRoot(document.getElementById('root')).render(<ProfilePreview />)
