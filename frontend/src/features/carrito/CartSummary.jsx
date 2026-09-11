import { formatClp } from './cartMoney.js'

// Vista de lectura: respeta los importes de CarritoResponse, no inventa envío ni descuentos.
export default function CartSummary({ cart }) {
  return (
    <div className="cart-layout">
      <section className="cart-card" aria-labelledby="cart-items-heading">
        <h2 id="cart-items-heading">Productos del ejemplo</h2>
        {cart.items.length === 0 ? (
          <div role="status">
            <h3>Carrito vacío en este ejemplo</h3>
            <p>Este escenario no contiene productos. No describe tu carrito real.</p>
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
            </li>
          ))}
        </ul>}
      </section>
      <aside className="cart-card" aria-labelledby="cart-total-heading">
        <h2 id="cart-total-heading">Resumen del ejemplo</h2>
        <dl className="cart-total"><dt>Total de productos (CLP)</dt><dd>{formatClp(cart.total)}</dd></dl>
        <p>No incluye envío ni descuentos. No reserva stock ni confirma un pedido.</p>
        <p>Todos los productos de un carrito deben pertenecer al mismo restaurante.</p>
      </aside>
    </div>
  )
}
