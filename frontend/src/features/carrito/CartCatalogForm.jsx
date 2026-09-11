import { useId, useRef, useState } from 'react'
import { CART_CATALOG } from './cartCatalogDemo.js'
import { formatClp } from './cartMoney.js'

export default function CartCatalogForm({ onAdd, disabled = false, saving = false }) {
  const id = useId()
  const [productId, setProductId] = useState(String(CART_CATALOG[0].productoId))
  const [quantity, setQuantity] = useState('1')
  const submitting = useRef(false)
  async function submit(event) {
    event.preventDefault()
    if (disabled || submitting.current) return
    submitting.current = true
    try { if (await onAdd(Number(productId), quantity)) setQuantity('1') }
    finally { submitting.current = false }
  }
  return (
    <form onSubmit={submit} noValidate aria-label="Agregar producto de prueba" aria-busy={saving}>
      <fieldset className="cart-edit-fields" disabled={disabled}>
        <legend className="cart-field-legend">Catálogo ficticio</legend>
        <label htmlFor={`${id}-product`}>Producto de prueba</label>
        <select id={`${id}-product`} value={productId} onChange={event => setProductId(event.target.value)}>
          {CART_CATALOG.map(product => <option key={product.productoId} value={product.productoId}>
            {product.nombre} — {product.restaurante} — {formatClp(product.precioUnitario)}{product.disponible ? '' : ' — No disponible'}
          </option>)}
        </select>
        <label htmlFor={`${id}-quantity`}>Cantidad para agregar</label>
        <input id={`${id}-quantity`} name="cantidad" inputMode="numeric" required maxLength={3} value={quantity}
          onChange={event => setQuantity(event.target.value)} aria-describedby={`${id}-hint`} />
        <small id={`${id}-hint`}>Entre 1 y 99 unidades. Si el producto ya existe, se suman a su cantidad actual.</small>
        <button type="submit" className="button button--primary">Agregar al ejemplo</button>
      </fieldset>
    </form>
  )
}
