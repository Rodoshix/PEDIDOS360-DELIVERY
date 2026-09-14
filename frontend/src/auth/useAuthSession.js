import { createContext, useContext } from 'react'

export const AuthSessionContext = createContext(null)

export function useAuthSession() {
  const session = useContext(AuthSessionContext)
  if (!session) throw new Error('useAuthSession requiere AuthSessionProvider.')
  return session
}
