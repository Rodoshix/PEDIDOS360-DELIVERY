import { ApiAccessError } from '../../auth/ApiAccessError.js'
import { ProfileError, validateProfileResponse } from './profileService.js'
import { normalizeProfileDraft, validateProfileDraft } from './profileForm.js'

function safeFailure(error, fallback) {
  if (error instanceof ProfileError) return new ProfileError(error.code)
  if (error instanceof ApiAccessError) {
    if (error.code === 'INTERACTION_REQUIRED') return new ProfileError('INTERACTION_REQUIRED')
    if (error.status === 401 || ['SESSION_REQUIRED', 'SESSION_CHANGED'].includes(error.code)) return new ProfileError('UNAUTHORIZED')
    if (error.status === 403) return new ProfileError('FORBIDDEN')
    if (error.status === 400) return new ProfileError('INVALID_INPUT')
    if (error.status === 409) return new ProfileError('CONFLICT')
    if (error.status === 404) return new ProfileError('NOT_FOUND')
  }
  return new ProfileError(fallback)
}

// Sin identidad, roles, tokens ni almacenamiento propios: los gestiona el cliente compartido.
export function createProfileHttpAdapter(client) {
  let loaded = false
  let profile = null
  return {
    async read({ signal } = {}) {
      loaded = false
      signal?.throwIfAborted()
      try {
        const response = await client.get('/usuarios/me', { signal })
        signal?.throwIfAborted()
        // Un 200 vacío/malformado no significa ausencia: solo el 404 la representa.
        if (response.status !== 200 || !response.data || typeof response.data !== 'object') {
          throw new ProfileError('INVALID_RESPONSE')
        }
        profile = validateProfileResponse(response.data)
        loaded = true
        return profile
      } catch (error) {
        signal?.throwIfAborted()
        if (error instanceof ApiAccessError && error.status === 404) {
          profile = null
          loaded = true
          return null
        }
        throw safeFailure(error, 'LOAD_FAILED')
      }
    },
    async save(draft, { signal } = {}) {
      signal?.throwIfAborted()
      if (!loaded) throw new ProfileError('NOT_CONFIGURED')
      if (profile?.activo === false) throw new ProfileError('FORBIDDEN')
      if (Object.keys(validateProfileDraft(draft)).length) throw new ProfileError('INVALID_INPUT')
      const payload = normalizeProfileDraft(draft)
      const expectedId = profile?.id
      try {
        const response = expectedId
          ? await client.put(`/usuarios/${expectedId}`, payload, { signal })
          : await client.post('/usuarios', payload, { signal })
        signal?.throwIfAborted()
        if (response.status !== (expectedId ? 200 : 201)) throw new ProfileError('INVALID_RESPONSE')
        const saved = validateProfileResponse(response.data)
        if (expectedId && saved.id !== expectedId) throw new ProfileError('INVALID_RESPONSE')
        profile = saved
        return saved
      } catch (error) {
        signal?.throwIfAborted()
        // Nunca volver a enviar automáticamente una escritura de resultado incierto.
        throw safeFailure(error, 'SAVE_FAILED')
      }
    },
  }
}
