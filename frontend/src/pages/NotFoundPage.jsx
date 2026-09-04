import { Link } from 'react-router'
import { ROUTE_PATHS } from '../routes/routePaths.js'

function NotFoundPage() {
  return (
    <section className="not-found">
      <div className="not-found__content">
        <p className="not-found__code">404</p>
        <h1>No encontramos esta página</h1>
        <p>La dirección puede estar equivocada o la página todavía no existe.</p>
        <Link className="button button--primary" to={ROUTE_PATHS.home}>
          Volver al inicio
        </Link>
      </div>
    </section>
  )
}

export default NotFoundPage
