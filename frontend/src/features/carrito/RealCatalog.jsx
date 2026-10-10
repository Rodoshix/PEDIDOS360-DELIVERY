import { useEffect, useRef, useState } from 'react'
import { commerceFailure } from './cartHttpAdapter.js'
import { formatClp } from './cartMoney.js'
import Button from '../../components/ui/Button.jsx'
import Badge from '../../components/ui/Badge.jsx'
import { Field, Select } from '../../components/ui/Field.jsx'
import { EmptyState } from '../../components/feedback/Feedback.jsx'
import LoadingState from '../../components/feedback/LoadingState.jsx'

export default function RealCatalog({ adapter, disabled, onAdd, onError }) {
  const [restaurants, setRestaurants] = useState(null)
  const [restaurant, setRestaurant] = useState('')
  const [products, setProducts] = useState(null)
  const [error, setError] = useState(null)
  const [revision, setRevision] = useState(0)
  const [loading, setLoading] = useState(true)
  const notice = useRef(null)
  useEffect(() => {
    const abort = new AbortController()
    async function load() {
      try {
        const next = await adapter.restaurants({ signal: abort.signal })
        if (abort.signal.aborted) return
        setRestaurants(next)
        if (restaurant && next.some(item => item.id === Number(restaurant))) {
          const items = await adapter.products(Number(restaurant), { signal: abort.signal })
          if (!abort.signal.aborted) setProducts(items)
        }
      } catch (failure) {
        if (!abort.signal.aborted) {
          const safe = commerceFailure(failure)
          setError(safe)
          onError(safe)
        }
      } finally { if (!abort.signal.aborted) setLoading(false) }
    }
    void load()
    return () => abort.abort()
  }, [adapter, restaurant, revision, onError])
  useEffect(() => { if (error) notice.current?.focus() }, [error])
  const selected = restaurants?.find(item => item.id === Number(restaurant))
  const states = { ABIERTO: 'Abierto', CERRADO: 'Cerrado', INACTIVO: 'Inactivo' }
  return <section className="client-catalog" aria-labelledby="real-catalog-heading" aria-busy={loading}>
    <div className="client-catalog__toolbar">
      <div><h2 id="real-catalog-heading">Elige un restaurante</h2>
        <p>Agregar incorpora una unidad. Puedes ajustar la cantidad en tu carrito.</p></div>
      <Button variant="secondary" disabled={disabled || loading}
        onClick={() => {
          setLoading(true); setError(null); onError(null); setProducts(null); setRevision(value => value + 1)
        }}>Actualizar catálogo</Button>
    </div>
    {loading && <LoadingState label="Consultando catálogo…" compact />}
    {error && <p className="ui-alert ui-alert--danger" ref={notice} tabIndex={-1} role="alert">{error.message}</p>}
    {!loading && !error && restaurants?.length === 0 && <div role="status"><EmptyState title="No hay restaurantes disponibles." /></div>}
    {restaurants?.length > 0 && <div className="client-catalog__selection">
      <Field as={Select} label="Restaurante" hint="Los productos corresponden al restaurante seleccionado."
        value={restaurant} disabled={disabled || loading} onChange={event => {
        setLoading(true); setError(null); onError(null); setProducts(null); setRestaurant(event.target.value)
      }}>
        <option value="">Selecciona un restaurante</option>
        {restaurants.map(item => <option key={item.id} value={item.id}>{item.nombre} — {states[item.estado]}</option>)}
      </Field>
      {selected && <div className="client-catalog__restaurant" role="status">
        <span>Restaurante seleccionado</span><strong>{selected.nombre}</strong>
        <Badge tone={selected.estado === 'ABIERTO' ? 'success' : 'warning'}>{states[selected.estado]}</Badge>
        {selected.estado !== 'ABIERTO' && <p>Este restaurante no está abierto. No puedes agregar sus productos.</p>}
      </div>}
    </div>}
    {!loading && !error && restaurants?.length > 0 && !restaurant && <p className="client-catalog__hint">Selecciona un restaurante para consultar sus productos.</p>}
    {!loading && !error && selected && <h2 className="client-catalog__products-heading">Productos de {selected.nombre}</h2>}
    {!loading && !error && products?.length === 0 && <div role="status"><EmptyState title="Este restaurante no tiene productos." /></div>}
    {!loading && !error && products?.length > 0 && <ul className="client-catalog__products" aria-label="Productos del restaurante">{products.map(item => <li key={item.id} className="client-catalog__product">
      <Badge tone={item.disponible ? 'success' : 'neutral'}>{item.disponible ? 'Disponible' : 'No disponible'}</Badge>
      <h3>{item.nombre}</h3><p className="client-catalog__price">{formatClp(item.precio)}</p>
      <Button
        disabled={disabled || !item.disponible || selected?.estado !== 'ABIERTO'}
        onClick={() => onAdd(item.id)} aria-label={`Agregar ${item.nombre}`}>Agregar al carrito</Button>
    </li>)}</ul>}
  </section>
}
