import { createExampleProfile } from './profileDemo.js'
import { normalizeProfileDraft, validateProfileDraft } from './profileForm.js'
import { ProfileError } from './profileService.js'

export const PROFILE_SCENARIOS = Object.freeze([
  ['example', 'Perfil existente'], ['empty', 'Sin perfil'],
  ['load-error', 'Consulta falla una vez'], ['save-error', 'Guardado falla una vez'],
  ['conflict', 'Conflicto al guardar una vez'], ['forbidden', 'Acceso denegado'], ['inactive', 'Perfil inactivo'],
].map(([value, label]) => Object.freeze({ value, label })))

function delay(ms, signal) {
  return new Promise((resolve, reject) => {
    const cancel = () => { clearTimeout(timer); reject(new DOMException('Operación cancelada', 'AbortError')) }
    const timer = setTimeout(() => { signal?.removeEventListener('abort', cancel); resolve() }, ms)
    if (signal?.aborted) cancel()
    else signal?.addEventListener('abort', cancel, { once: true })
  })
}

export function createProfileDemoAdapter({ scenario = 'example', delayMs = 600 } = {}) {
  if (!PROFILE_SCENARIOS.some(item => item.value === scenario)) throw new Error('Escenario de perfil desconocido.')
  let profile = scenario === 'empty' ? null : { ...createExampleProfile(), activo: scenario !== 'inactive' }
  let failedRead = false
  let failedSave = false
  return {
    async read({ signal } = {}) {
      await delay(delayMs, signal)
      signal?.throwIfAborted()
      if (scenario === 'forbidden') throw new ProfileError('FORBIDDEN')
      if (scenario === 'load-error' && !failedRead) { failedRead = true; throw new ProfileError('LOAD_FAILED') }
      return profile ? { ...profile } : null
    },
    async save(draft, { signal } = {}) {
      await delay(delayMs, signal)
      signal?.throwIfAborted()
      if (scenario === 'forbidden' || profile?.activo === false) throw new ProfileError('FORBIDDEN')
      if (Object.keys(validateProfileDraft(draft)).length) throw new ProfileError('INVALID_INPUT')
      if (['save-error', 'conflict'].includes(scenario) && !failedSave) {
        failedSave = true
        throw new ProfileError(scenario === 'conflict' ? 'CONFLICT' : 'SAVE_FAILED')
      }
      profile = { ...(profile || createExampleProfile()), ...normalizeProfileDraft(draft), actualizadoEn: new Date().toISOString() }
      return { ...profile }
    },
  }
}
