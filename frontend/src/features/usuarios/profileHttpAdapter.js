import { ApiAccessError } from '../../auth/ApiAccessError.js'
import { ProfileError } from './profileService.js'

// Sin identidad, roles, tokens ni almacenamiento propios: los gestiona el cliente compartido.
export function createProfileHttpAdapter(client) {
  return {
    async read({ signal } = {}) {
      signal?.throwIfAborted()
      try {
        const response = await client.get('/usuarios/me', { signal })
        signal?.throwIfAborted()
        // Un 200 vacío/malformado no significa ausencia: solo el 404 la representa.
        if (response.status !== 200 || !response.data || typeof response.data !== 'object') {
          throw new ProfileError('INVALID_RESPONSE')
        }
        return response.data
      } catch (error) {
        signal?.throwIfAborted()
        if (error instanceof ProfileError) throw new ProfileError(error.code)
        if (error instanceof ApiAccessError) {
          if (error.status === 404) return null
          if (error.code === 'INTERACTION_REQUIRED') throw new ProfileError('INTERACTION_REQUIRED')
          if (error.status === 401 || ['SESSION_REQUIRED', 'SESSION_CHANGED'].includes(error.code)) throw new ProfileError('UNAUTHORIZED')
          if (error.status === 403) throw new ProfileError('FORBIDDEN')
        }
        throw new ProfileError('LOAD_FAILED')
      }
    },
    async save() {
      throw new ProfileError('NOT_CONFIGURED')
    },
  }
}
