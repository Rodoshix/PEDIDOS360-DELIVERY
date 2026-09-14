import { useEffect, useRef, useState } from 'react'
import { commerceFailure } from './cartHttpAdapter.js'
import { formatClp } from './cartMoney.js'

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
  return <section className="cart-card" aria-labelledby="real-catalog-heading" aria-busy={loading}>
    <h2 id="real-catalog-heading">Catálogo de restaurantes</h2>
    <p>Productos consultados mediante el BFF. Agregar incorpora una unidad; puedes ajustar la cantidad en el carrito.</p>
    {loading && <p role="status">Consultando catálogo…</p>}
    {error && <p ref={notice} tabIndex={-1} role="alert">{error.message}</p>}
    {!loading && !error && restaurants?.length === 0 && <p role="status">No hay restaurantes disponibles.</p>}
    {restaurants?.length > 0 && <label>Restaurante
      <select value={restaurant} disabled={disabled || loading} onChange={event => {
        setLoading(true); setError(null); onError(null); setProducts(null); setRestaurant(event.target.value)
      }}>
        <option value="">Selecciona un restaurante</option>
        {restaurants.map(item => <option key={item.id} value={item.id}>{item.nombre} — {item.estado}</option>)}
      </select>
    </label>}
    {!loading && !error && products?.length === 0 && <p role="status">Este restaurante no tiene productos.</p>}
    {!loading && !error && products && <ul className="cart-items">{products.map(item => <li key={item.id} className="cart-item">
      <h3>{item.nombre}</h3><p>{formatClp(item.precio)}</p>
      {!item.disponible && <p>No disponible</p>}
      <button type="button" className="button button--primary"
        disabled={disabled || !item.disponible || selected?.estado !== 'ABIERTO'}
        onClick={() => onAdd(item.id)}>Agregar {item.nombre}</button>
    </li>)}</ul>}
    {selected && selected.estado !== 'ABIERTO' && <p>Este restaurante no está abierto.</p>}
    <button type="button" className="button button--secondary" disabled={disabled || loading}
      onClick={() => {
        setLoading(true); setError(null); onError(null); setProducts(null); setRevision(value => value + 1)
      }}>Actualizar catálogo</button>
  </section>
}
