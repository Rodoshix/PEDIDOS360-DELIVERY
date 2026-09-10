const localOrigin = 'https://pedidos360.invalid'
const storageKey = 'pedidos360.auth.returnDestination'
const maxAgeMs = 15 * 60 * 1000

export function createReturnDestinationStore(storage, createId = () => crypto.randomUUID(), now = () => Date.now()) {
  function clear() {
    try { storage.removeItem(storageKey) } catch { /* No interrumpir logout si el almacenamiento está bloqueado. */ }
  }

  return {
    clear,
    save(destination) {
      const id = createId()
      // Solo este identificador opaco viaja a Microsoft; ruta y parámetros quedan en la pestaña.
      storage.setItem(storageKey, JSON.stringify({ id, destination: safeReturnDestination(destination), createdAt: now() }))
      return id
    },
    consume(id) {
      try {
        const raw = storage.getItem(storageKey)
        storage.removeItem(storageKey)
        const saved = JSON.parse(raw)
        const age = now() - saved?.createdAt
        if (!id || saved?.id !== id || !Number.isFinite(age) || age < 0 || age > maxAgeMs) return '/'
        return safeReturnDestination(saved.destination)
      } catch {
        clear()
        return '/'
      }
    },
  }
}

// El destino es navegación, nunca una URL externa ni una fuente de permisos.
export function safeReturnDestination(value) {
  if (typeof value !== 'string' || value.length > 2048 || !value.startsWith('/')) return '/'
  try {
    let decoded = value
    for (let depth = 0; depth < 4; depth += 1) {
      const hasUnsafeCharacters = [...decoded].some(char => char === '\\' || char.charCodeAt(0) <= 32 || char.charCodeAt(0) === 127)
      if (!decoded.startsWith('/') || decoded.startsWith('//') || hasUnsafeCharacters) return '/'
      const parsed = new URL(decoded, localOrigin)
      if (parsed.origin !== localOrigin || parsed.pathname.startsWith('//')) return '/'
      const next = decodeURIComponent(decoded)
      if (next === decoded) break
      if (depth === 3) return '/'
      decoded = next
    }
    const url = new URL(value, localOrigin)
    // No reenviar respuestas OAuth como parte del siguiente inicio de sesión.
    const authKeys = ['code', 'state', 'id_token', 'access_token', 'error', 'error_description']
    const hashParams = new URLSearchParams(url.hash.slice(1))
    if (authKeys.some(key => url.searchParams.has(key) || hashParams.has(key))) return '/'
    return url.pathname + url.search + url.hash
  } catch {
    return '/'
  }
}

export function restoreReturnDestination(history, destination) {
  if (destination == null) return
  // Se ejecuta solo tras una respuesta de login válida, antes de montar BrowserRouter.
  history.replaceState(null, '', safeReturnDestination(destination))
}
