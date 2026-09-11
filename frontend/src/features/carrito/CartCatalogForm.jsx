import { useId, useState } from 'react'
import { CART_CATALOG } from './cartCatalogDemo.js'
import { formatClp } from './cartMoney.js'

export default function CartCatalogForm({ onAdd, disabled = false }) {
  const id = useId()
  const [productId, setProductId] = useState(String(CART_CATALOG[0].productoId))
  const [quantity, setQuantity] = useState('1')
  function submit(event) {
    event.preventDefault()
    if (!disabled && onAdd(Number(productId), quantity)) setQuantity('1')
  }
  return (
    <form onSubmit={submit} noValidate aria-label="Agregar producto de prueba">
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
