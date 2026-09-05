import { useEffect, useMemo, useState } from 'react'
import { useMsal } from '@azure/msal-react'
import { InteractionStatus } from '@azure/msal-browser'
import { createSessionActions, selectSessionAccount } from './session.js'

import { AuthSessionContext } from './useAuthSession.js'

export function AuthSessionProvider({ tenantId, initialError, children }) {
  const { instance, accounts, inProgress } = useMsal()
  const [error, setError] = useState(initialError)
  const [pending, setPending] = useState(null)
  const actions = useMemo(() => createSessionActions(instance, {
    onPending: setPending,
    onError: setError,
  }), [instance])
  const account = selectSessionAccount(accounts, instance.getActiveAccount(), tenantId)
  const busy = pending !== null || inProgress !== InteractionStatus.None

  useEffect(() => {
    if (inProgress === InteractionStatus.None) instance.setActiveAccount(account)
  }, [instance, account, inProgress])

  const session = {
    account,
    error,
    busy,
    pending,
    login: () => { if (!busy) return actions.login() },
    logout: () => { if (!busy) return actions.logout(account) },
  }

  return <AuthSessionContext.Provider value={session}>{children}</AuthSessionContext.Provider>
}
