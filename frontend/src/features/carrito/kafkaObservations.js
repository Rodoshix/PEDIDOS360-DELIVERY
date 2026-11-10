/** Validate informational observations independently of cart/catalog write contracts. */
export function observation(value, kind) {
  if (!value || typeof value !== 'object' || !longString(value.id) || typeof value.conocido !== 'boolean') throw new Error('Invalid observation')
  if (!value.conocido) return { id: value.id, conocido: false }
  const allowed = kind === 'restaurant' ? ['ABIERTO', 'CERRADO', 'INACTIVO'] : ['true', 'false']
  if (!allowed.includes(value.valor) || !longString(value.version) || typeof value.observadoEn !== 'string'
      || !Number.isFinite(Date.parse(value.observadoEn))) throw new Error('Invalid observation')
  return { id: value.id, conocido: true, valor: value.valor, version: value.version, observadoEn: value.observadoEn }
}
function longString(value) {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
}
export function observations(value, kind) {
  if (kind === 'restaurant') return [observation(value, kind)]
  if (!value || value.informativo !== true || !Array.isArray(value.items)) throw new Error('Invalid observations')
  return value.items.map(item => observation(item, kind))
}
