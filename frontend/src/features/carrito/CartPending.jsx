export default function CartPending() {
  return (
    <section className="cart-card" aria-labelledby="cart-pending-heading">
      <p className="cart-badge">Integración pendiente</p>
      <h2 id="cart-pending-heading">Carrito aún no consultado</h2>
      <p role="status">Todavía no consultamos el servicio de Carrito. No sabemos si tienes productos guardados.</p>
      <p>Cuando conectemos el servicio, aquí aparecerán tus productos, cantidades y total.</p>
    </section>
  )
}
