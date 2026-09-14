import { normalizeProfileDraft, validateProfileDraft } from './profileForm.js'

const messages = Object.freeze({
  NOT_CONFIGURED: 'El servicio de perfil no está conectado.',
  LOAD_FAILED: 'No se pudo consultar el perfil. Puedes reintentar la consulta.',
  SAVE_FAILED: 'No se pudo confirmar el guardado. Tu borrador se conserva. Consulta el perfil antes de repetir: el servidor pudo haber aplicado los cambios.',
  FORBIDDEN: 'No tienes permiso para acceder a este perfil o el perfil está inactivo.',
  UNAUTHORIZED: 'La API rechazó la sesión. Vuelve a iniciar sesión con Microsoft.',
  INTERACTION_REQUIRED: 'Microsoft necesita confirmar el acceso a la API.',
  CONFLICT: 'El perfil entró en conflicto. Tu borrador se conserva; consulta el estado actual antes de volver a guardar.',
  NOT_FOUND: 'El perfil ya no está disponible. Tu borrador se conserva; vuelve a consultar el perfil.',
  INVALID_INPUT: 'Revisa los campos del perfil antes de continuar.',
  INVALID_RESPONSE: 'El servicio devolvió un perfil inválido. No se aplicó esa respuesta.',
})

export class ProfileError extends Error {
  constructor(code) {
    super(Object.hasOwn(messages, code) ? messages[code] : messages.LOAD_FAILED)
    this.name = 'ProfileError'
    this.code = Object.hasOwn(messages, code) ? code : 'LOAD_FAILED'
  }
}

export function validateProfileResponse(value) {
  if (!value || !Number.isSafeInteger(value.id) || value.id <= 0 || typeof value.activo !== 'boolean'
    || ['nombre', 'apellido', 'email'].some(key => typeof value[key] !== 'string')
    || (value.telefono !== null && typeof value.telefono !== 'string')
    || ['creadoEn', 'actualizadoEn'].some(key => typeof value[key] !== 'string' || !Number.isFinite(Date.parse(value[key])))
    || Object.keys(validateProfileDraft(value)).length) throw new ProfileError('INVALID_RESPONSE')
  return Object.freeze({ id: value.id, ...normalizeProfileDraft(value), activo: value.activo,
    creadoEn: value.creadoEn, actualizadoEn: value.actualizadoEn })
}

// Instancia por pantalla/cuenta. No conoce MSAL, HTTP ni almacenamiento del navegador.
export function createProfileController() {
  let state = Object.freeze({ status: 'idle', profile: null, error: null, operation: null })
  let adapter = null
  let request = null
  let generation = 0
  const listeners = new Set()
  const publish = next => { state = Object.freeze(next); listeners.forEach(listener => listener()) }

  function cancelPending() {
    generation += 1
    request?.abort()
    request = null
  }

  async function run(operation, draft) {
    if (request) return false // Bloqueo inmediato, incluso antes del siguiente render.
    const currentGeneration = ++generation
    const abort = new AbortController()
    request = abort
    const previous = state.profile
    const currentAdapter = adapter
    publish({ ...state, status: operation === 'load' ? 'loading' : 'saving', error: null, operation })
    try {
      abort.signal.throwIfAborted()
      if (!currentAdapter) throw new ProfileError('NOT_CONFIGURED')
      let result
      if (operation === 'load') result = await currentAdapter.read({ signal: abort.signal })
      else {
        if (previous?.activo === false) throw new ProfileError('FORBIDDEN')
        if (Object.keys(validateProfileDraft(draft)).length) throw new ProfileError('INVALID_INPUT')
        result = await currentAdapter.save(normalizeProfileDraft(draft), { signal: abort.signal })
      }
      if (abort.signal.aborted || currentGeneration !== generation) return false
      const profile = operation === 'load' && result === null ? null : validateProfileResponse(result)
      publish({ status: profile ? 'ready' : 'empty', profile, error: null, operation: null })
      return true
    } catch (error) {
      if (abort.signal.aborted || currentGeneration !== generation) return false
      // Recrear incluso los errores conocidos: nunca propagar mensajes o causas del adaptador.
      const safeError = new ProfileError(error instanceof ProfileError ? error.code : operation === 'load' ? 'LOAD_FAILED' : 'SAVE_FAILED')
      publish({ status: 'error', profile: previous, error: safeError, operation })
      return false
    } finally {
      if (currentGeneration === generation) request = null
    }
  }

  return {
    getSnapshot: () => state,
    subscribe(listener) { listeners.add(listener); return () => listeners.delete(listener) },
    connect(nextAdapter) {
      cancelPending()
      adapter = nextAdapter
      publish({ status: 'idle', profile: null, error: null, operation: null })
    },
    load: () => run('load'),
    save: draft => state.status === 'idle' || (state.operation === 'load' && state.status === 'error')
      ? Promise.resolve(false) : run('save', draft),
    clearError() {
      // Descartar un borrador no debe convertir un fallo de consulta en ausencia.
      if (!request && state.error && state.operation === 'save') {
        publish({ status: state.profile ? 'ready' : 'empty', profile: state.profile, error: null, operation: null })
      }
    },
    cancelPending,
  }
}
