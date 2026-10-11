export class CatalogAdminError extends Error {
  constructor(code = 'LOAD_FAILED') {
    super({ FORBIDDEN: 'Esta operación requiere permiso de administrador.', UNAUTHORIZED: 'Vuelve a iniciar sesión con Microsoft.',
      INTERACTION_REQUIRED: 'Microsoft necesita confirmar el acceso a la API.',
      LOAD_FAILED: 'No se pudo consultar el catálogo. Puedes reintentar.', INVALID_RESPONSE: 'La respuesta del catálogo no es válida.',
      WRITE_FAILED: 'No se pudo confirmar el cambio. Actualiza el listado antes de repetir: el servidor pudo aplicarlo.',
      RECONCILE: 'Actualiza el listado antes de realizar otro cambio.', INVALID_COMMAND: 'Revisa los datos del formulario.',
      NOT_FOUND: 'El registro ya no está disponible. Actualiza el listado.', CONFLICT: 'El registro cambió. Actualiza el listado antes de continuar.' }[code] || 'No se pudo completar la operación.')
    this.code = code
  }
}
export const positiveId = value => Number.isSafeInteger(Number(value)) && Number(value) > 0 && /^\d+$/.test(String(value))
export function validateCatalogDraft(kind, draft) {
  const errors = {}
  const text = (field, max, required = false) => {
    if (typeof draft[field] !== 'string' || (required && !draft[field].trim())) errors[field] = 'Completa este campo.'
    else if (draft[field].length > max) errors[field] = `Hasta ${max} caracteres.`
  }
  text('nombre', 120, true); text('descripcion', 500)
  if (kind === 'restaurantes') {
    text('direccion', 255)
    if (!['ABIERTO', 'CERRADO', 'INACTIVO'].includes(draft.estado)) errors.estado = 'Selecciona un estado válido.'
  } else {
    text('categoria', 80, true)
    if (!positiveId(draft.restauranteId)) errors.restauranteId = 'Selecciona un restaurante.'
    if (!/^\d{1,8}(\.\d{1,2})?$/.test(String(draft.precio)) || Number(draft.precio) < 0.01) errors.precio = 'Precio entre 0,01 y 99.999.999,99, con hasta dos decimales. Usa punto decimal.'
    if (typeof draft.disponible !== 'boolean') errors.disponible = 'Selecciona la disponibilidad.'
  }
  return errors
}
export function createCatalogAdminController(adapter) {
  let state = { status: 'idle', rows: [], restaurants: [], error: null }, sequence = 0, disposed = false, writing = false, ready = false
  const listeners = new Set(), requests = new Set()
  const publish = next => { if (!disposed) { state = { ...state, ...next }; listeners.forEach(fn => fn(state)) } }
  const request = () => { const abort = new AbortController(); requests.add(abort); return abort }
  async function load(filter = '') {
    if (disposed || writing) return false
    const turn = ++sequence, abort = request(); ready = false; publish({ status: 'loading', error: null })
    try {
      const data = await adapter.list(filter, abort.signal)
      if (disposed || turn !== sequence) return false
      ready = true; publish({ ...data, status: 'ready', error: null }); return true
    } catch (error) {
      if (!disposed && turn === sequence) publish({ status: 'error', error: error instanceof CatalogAdminError ? error : new CatalogAdminError() })
      return false
    } finally { requests.delete(abort) }
  }
  return {
    getState: () => state,
    subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn) }, load,
    async write(command, filter = '') {
      if (disposed || writing || state.status === 'loading') return false
      if (!ready) { publish({ status: 'error', error: new CatalogAdminError('RECONCILE') }); return false }
      writing = true; const abort = request(); publish({ status: 'saving', error: null })
      try {
        await adapter.write(command, abort.signal)
        if (disposed) return false
        writing = false; await load(filter); return !disposed
      } catch (error) {
        ready = false; publish({ status: 'error', error: error instanceof CatalogAdminError ? error : new CatalogAdminError('WRITE_FAILED') }); return false
      } finally { writing = false; requests.delete(abort) }
    },
    dispose() { disposed = true; ++sequence; requests.forEach(abort => abort.abort()); listeners.clear() },
  }
}
