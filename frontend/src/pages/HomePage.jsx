const featureCards = [
  {
    number: '01',
    title: 'Elige tu restaurante',
    description: 'Revisa restaurantes y encuentra los productos que quieres pedir.',
  },
  {
    number: '02',
    title: 'Arma tu pedido',
    description: 'Agrega productos al carrito y confirma los datos de tu compra.',
  },
  {
    number: '03',
    title: 'Sigue la entrega',
    description: 'Consulta el estado del pedido hasta que llegue a tu dirección.',
  },
]

function HomePage() {
  return (
    <>
      <section className="hero-section">
        <div className="container hero-section__content">
          <div>
            <p className="eyebrow">Pedidos360 Delivery</p>
            <h1>Tu pedido, simple y en un solo lugar.</h1>
            <p className="hero-section__description">
              Una plataforma para descubrir restaurantes, pedir tus platos favoritos y seguir
              cada entrega de principio a fin.
            </p>

            <div className="status-card" role="status">
              <span className="status-card__dot" aria-hidden="true" />
              Base del frontend preparada para integrar los módulos del equipo
            </div>
          </div>

          <div className="hero-visual" aria-hidden="true">
            <span className="hero-visual__plate">360°</span>
            <span className="hero-visual__label">DELIVERY</span>
          </div>
        </div>
      </section>

      <section className="steps-section" aria-labelledby="steps-title">
        <div className="container">
          <div className="section-heading">
            <p className="eyebrow">Cómo funcionará</p>
            <h2 id="steps-title">Del restaurante hasta tu puerta</h2>
          </div>

          <div className="feature-grid">
            {featureCards.map((feature) => (
              <article className="feature-card" key={feature.number}>
                <span className="feature-card__number" aria-hidden="true">
                  {feature.number}
                </span>
                <h3>{feature.title}</h3>
                <p>{feature.description}</p>
              </article>
            ))}
          </div>
        </div>
      </section>
    </>
  )
}

export default HomePage
