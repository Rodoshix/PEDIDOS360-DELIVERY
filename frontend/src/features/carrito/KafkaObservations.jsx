import { useEffect, useState } from 'react'
import { observations } from './kafkaObservations.js'

/** Optional observations never gate existing reads or writes, and never mutate a cart. */
export default function KafkaObservations({ restaurantId, cart, enabled = import.meta.env.VITE_KAFKA_INFORMATION_ENABLED === 'true' }) {
  const [state, setState] = useState(null)
  const [revision, setRevision] = useState(0)
  const kind = restaurantId ? 'restaurant' : 'cart'
  const requestKey = JSON.stringify([kind, restaurantId, cart?.id, cart?.version, cart?.items?.map(item => item.productoId), revision])
  useEffect(() => {
    if (!enabled || (!restaurantId && !cart)) return
    const abort = new AbortController()
    import('../../services/httpClient.js').then(async ({ default: client }) => {
      const path = restaurantId ? `/productos/restaurante/${restaurantId}/estado-restaurante` : '/carrito/disponibilidad'
      const response = await client.get(path, { signal: abort.signal })
      if (!abort.signal.aborted) setState({ requestKey, items: observations(response.data, kind) })
    }).catch(() => { if (!abort.signal.aborted) setState({ requestKey, error: true }) })
    return () => abort.abort()
  }, [enabled, restaurantId, cart, kind, revision, requestKey])
  if (!enabled || (!restaurantId && !cart)) return null
  const current = state?.requestKey === requestKey ? state : null
  const items = current?.items || []
  return <aside aria-label="Observaciones del catálogo" aria-live="polite">
    <p>Información adicional del catálogo; no confirma disponibilidad de compra.</p>
    {!current && <p>Consultando información adicional…</p>}
    {current?.error && <p>Información adicional no disponible. El catálogo y tu carrito siguen disponibles.</p>}
    {items.map(item => <p key={item.id}>{kind === 'restaurant' ? 'Estado informativo del restaurante' : `Producto ${item.id}`}: {' '}
      {!item.conocido ? 'Sin información recibida.' : <>{kind === 'restaurant' ? item.valor : item.valor === 'false' ? 'No disponible; revisa este producto.' : 'Disponible en la última observación.'}
        {' '}Observado: <time dateTime={item.observadoEn}>{new Date(item.observadoEn).toLocaleString()}</time>.</>}</p>)}
    <button type="button" disabled={!current} onClick={() => setRevision(value => value + 1)}>Actualizar información adicional</button>
  </aside>
}
