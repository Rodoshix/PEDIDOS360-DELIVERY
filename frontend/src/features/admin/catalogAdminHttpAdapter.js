import { CatalogAdminError, positiveId, validateCatalogDraft } from './catalogAdminService.js'

export function adminFailure(error, write = false) {
  if (error instanceof CatalogAdminError) return error
  const code = error?.code === 'INTERACTION_REQUIRED' ? 'INTERACTION_REQUIRED'
    : error?.status === 403 ? 'FORBIDDEN' : error?.status === 401 ? 'UNAUTHORIZED'
      : error?.status === 404 ? 'NOT_FOUND' : error?.status === 409 ? 'CONFLICT' : write ? 'WRITE_FAILED' : 'LOAD_FAILED'
  return new CatalogAdminError(code)
}
function row(kind, value) {
  if (!value || !positiveId(value.id) || typeof value.nombre !== 'string'
      || (kind === 'restaurantes' ? !['ABIERTO', 'CERRADO', 'INACTIVO'].includes(value.estado)
        : !positiveId(value.restauranteId) || typeof value.disponible !== 'boolean' || !Number.isFinite(Number(value.precio)) || Number(value.precio) < .01)) throw new CatalogAdminError('INVALID_RESPONSE')
  // Proyectar solo campos de catálogo; no conservar headers ni respuestas de Axios.
  return kind === 'restaurantes' ? { id: Number(value.id), nombre: value.nombre, descripcion: value.descripcion ?? '', direccion: value.direccion ?? '', estado: value.estado }
    : { id: Number(value.id), restauranteId: Number(value.restauranteId), nombre: value.nombre, descripcion: value.descripcion ?? '', categoria: value.categoria ?? '', precio: value.precio, disponible: value.disponible }
}
export function createCatalogAdminHttpAdapter(client, kind) {
  if (!['restaurantes', 'productos'].includes(kind)) throw new CatalogAdminError('INVALID_COMMAND')
  async function get(path, type, signal, list = false) {
    const response = await client.get(path, { signal }); signal?.throwIfAborted()
    if (response.status !== 200 || (list && !Array.isArray(response.data))) throw new CatalogAdminError('INVALID_RESPONSE')
    return list ? response.data.map(value => row(type, value)) : row(type, response.data)
  }
  return {
    async list(filter, signal) {
      try {
        if (filter && !positiveId(filter)) throw new CatalogAdminError('INVALID_COMMAND')
        const path = kind === 'productos' && filter ? `/productos/restaurante/${Number(filter)}` : `/${kind}`
        const [rows, restaurants] = await Promise.all([get(path, kind, signal, true), kind === 'productos' ? get('/restaurantes', 'restaurantes', signal, true) : Promise.resolve([])])
        return { rows, restaurants }
      } catch (error) { throw adminFailure(error) }
    },
    async detail(id, signal) {
      try { if (!positiveId(id)) throw new CatalogAdminError('INVALID_COMMAND'); return await get(`/${kind}/${Number(id)}`, kind, signal) }
      catch (error) { throw adminFailure(error) }
    },
    async write(command, signal) {
      try {
        const id = command.id
        if (id !== undefined && !positiveId(id)) throw new CatalogAdminError('INVALID_COMMAND')
        let response
        if (command.type === 'save') {
          if (Object.keys(validateCatalogDraft(kind, command.draft)).length) throw new CatalogAdminError('INVALID_COMMAND')
          const d = command.draft
          const body = kind === 'restaurantes' ? { nombre: d.nombre.trim(), descripcion: d.descripcion, direccion: d.direccion, estado: d.estado }
            : { nombre: d.nombre.trim(), descripcion: d.descripcion, categoria: d.categoria.trim(), restauranteId: Number(d.restauranteId), precio: Number(d.precio), disponible: d.disponible }
          response = id === undefined ? await client.post(`/${kind}`, body, { signal }) : await client.put(`/${kind}/${Number(id)}`, body, { signal })
          if (response.status !== (id === undefined ? 201 : 200)) throw new CatalogAdminError('WRITE_FAILED')
          row(kind, response.data)
        } else if (command.type === 'deactivate' && kind === 'restaurantes' && id !== undefined) {
          response = await client.delete(`/restaurantes/${Number(id)}`, { signal })
          if (response.status !== 204) throw new CatalogAdminError('WRITE_FAILED')
        } else if (command.type === 'availability' && kind === 'productos' && id !== undefined && typeof command.disponible === 'boolean') {
          response = await client.patch(`/productos/${Number(id)}/disponibilidad?disponible=${command.disponible}`, null, { signal })
          if (response.status !== 200) throw new CatalogAdminError('WRITE_FAILED'); row(kind, response.data)
        } else throw new CatalogAdminError('INVALID_COMMAND')
        signal?.throwIfAborted()
      } catch (error) { throw adminFailure(error, true) }
    },
  }
}
