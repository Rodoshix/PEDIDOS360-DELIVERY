import { formatClp } from './cartMoney.js'
import CartQuantityForm from './CartQuantityForm.jsx'

// Respeta los importes de CarritoResponse. Edición opcional; sin envío ni descuentos inventados.
export default function CartSummary({ cart, onQuantity, onRemove, disabled = false, saving = false, mode = 'demo' }) {
  return (
    <div className="cart-layout">
      <section className="cart-card" aria-labelledby="cart-items-heading">
        <h2 id="cart-items-heading">{mode === 'real' ? 'Productos de tu carrito' : 'Productos del ejemplo'}</h2>
        {cart.items.length === 0 ? (
          <div role="status">
            <h3>{mode === 'real' ? 'Tu carrito está vacío' : 'Carrito vacío en este ejemplo'}</h3>
            <p>{mode === 'real' ? 'Elige un restaurante y agrega productos del catálogo.' : 'Este escenario no contiene productos. No describe tu carrito real.'}</p>
          </div>
        ) : <ul className="cart-items">
          {cart.items.map(item => (
            <li key={item.productoId} className="cart-item">
              <h3>{item.nombre}</h3>
              <dl className="cart-item__data">
                <div><dt>Precio unitario</dt><dd>{formatClp(item.precioUnitario)}</dd></div>
                <div><dt>Cantidad</dt><dd>{item.cantidad}</dd></div>
                <div><dt>Subtotal</dt><dd>{formatClp(item.subtotal)}</dd></div>
              </dl>
              {onQuantity && <CartQuantityForm key={`${item.productoId}:${item.cantidad}`} item={item} onChange={onQuantity} disabled={disabled} saving={saving} />}
              {onRemove && <button type="button" className="button button--secondary" disabled={disabled}
                onClick={event => onRemove(item, event.currentTarget)}>Eliminar {item.nombre}</button>}
            </li>
          ))}
        </ul>}
      </section>
      <aside className="cart-card" aria-labelledby="cart-total-heading">
        <h2 id="cart-total-heading">{mode === 'real' ? 'Resumen de tu carrito' : 'Resumen del ejemplo'}</h2>
        <dl className="cart-total"><dt>Total de productos (CLP)</dt><dd>{formatClp(cart.total)}</dd></dl>
        <p>No incluye envío ni descuentos. No reserva stock ni confirma un pedido.</p>
        <p>Todos los productos de un carrito deben pertenecer al mismo restaurante.</p>
      </aside>
    </div>
  )
}
