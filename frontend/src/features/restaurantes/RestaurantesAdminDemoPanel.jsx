import { useState } from 'react'

const RESTAURANTES_INICIALES = [
  {
    id: 1,
    nombre: 'Burger House',
    descripcion: 'Hamburguesas artesanales y papas fritas.',
    direccion: 'Av. Providencia 1234',
    estado: 'ABIERTO',
  },
  {
    id: 2,
    nombre: 'Pizza Central',
    descripcion: 'Pizzas familiares y acompañamientos.',
    direccion: 'Av. Apoquindo 4567',
    estado: 'CERRADO',
  },
]

const FORM_INICIAL = {
  nombre: '',
  descripcion: '',
  direccion: '',
  estado: 'ABIERTO',
}

export default function RestaurantesAdminDemoPanel() {
  const [restaurantes, setRestaurantes] = useState(RESTAURANTES_INICIALES)
  const [form, setForm] = useState(FORM_INICIAL)
  const [editandoId, setEditandoId] = useState(null)
  const [error, setError] = useState('')
  const [mensaje, setMensaje] = useState('')

  function actualizarCampo(event) {
    const { name, value } = event.target

    setForm(current => ({
      ...current,
      [name]: value,
    }))
  }

  function validar() {
    if (!form.nombre.trim()) {
      return 'El nombre del restaurante es obligatorio.'
    }

    if (!form.direccion.trim()) {
      return 'La dirección del restaurante es obligatoria.'
    }

    return ''
  }

  function limpiarFormulario() {
    setForm(FORM_INICIAL)
    setEditandoId(null)
    setError('')
  }

  function guardar(event) {
    event.preventDefault()

    const errorValidacion = validar()

    if (errorValidacion) {
      setError(errorValidacion)
      setMensaje('')
      return
    }

    setError('')

    if (editandoId !== null) {
      setRestaurantes(current =>
        current.map(restaurante =>
          restaurante.id === editandoId
            ? { ...restaurante, ...form }
            : restaurante
        )
      )

      setMensaje('Restaurante actualizado en el ejemplo local.')
    } else {
      const nuevo = {
        id: Date.now(),
        ...form,
      }

      setRestaurantes(current => [...current, nuevo])
      setMensaje('Restaurante creado en el ejemplo local.')
    }

    limpiarFormulario()
  }

  function editar(restaurante) {
    setForm({
      nombre: restaurante.nombre,
      descripcion: restaurante.descripcion,
      direccion: restaurante.direccion,
      estado: restaurante.estado,
    })

    setEditandoId(restaurante.id)
    setError('')
    setMensaje('')
  }

  function desactivar(id) {
    setRestaurantes(current =>
      current.map(restaurante =>
        restaurante.id === id
          ? { ...restaurante, estado: 'INACTIVO' }
          : restaurante
      )
    )

    setMensaje('Restaurante desactivado en el ejemplo local.')
  }

  return (
    <div className="restaurantes-admin">
      <p className="restaurantes-notice" role="status">
        {mensaje || 'Datos ficticios: los cambios no se guardan en el backend.'}
      </p>

      {error && (
        <div className="restaurantes-error" role="alert">
          {error}
        </div>
      )}

      <section className="restaurantes-card">
        <h2>{editandoId !== null ? 'Editar restaurante' : 'Crear restaurante'}</h2>

        <form className="restaurantes-form" onSubmit={guardar}>
          <label>
            Nombre
            <input
              type="text"
              name="nombre"
              value={form.nombre}
              onChange={actualizarCampo}
            />
          </label>

          <label>
            Descripción
            <textarea
              name="descripcion"
              value={form.descripcion}
              onChange={actualizarCampo}
            />
          </label>

          <label>
            Dirección
            <input
              type="text"
              name="direccion"
              value={form.direccion}
              onChange={actualizarCampo}
            />
          </label>

          <label>
            Estado
            <select
              name="estado"
              value={form.estado}
              onChange={actualizarCampo}
            >
              <option value="ABIERTO">Abierto</option>
              <option value="CERRADO">Cerrado</option>
              <option value="INACTIVO">Inactivo</option>
            </select>
          </label>

          <div className="restaurantes-actions">
            <button type="submit" className="button button--primary">
              {editandoId !== null ? 'Guardar cambios' : 'Crear restaurante'}
            </button>

            {editandoId !== null && (
              <button
                type="button"
                className="button button--secondary"
                onClick={limpiarFormulario}
              >
                Cancelar edición
              </button>
            )}
          </div>
        </form>
      </section>

      <section className="restaurantes-card">
        <h2>Restaurantes registrados</h2>

        {restaurantes.length === 0 ? (
          <p>No hay restaurantes registrados.</p>
        ) : (
          <ul className="restaurantes-list">
            {restaurantes.map(restaurante => (
              <li key={restaurante.id} className="restaurantes-item">
                <div>
                  <h3>{restaurante.nombre}</h3>
                  <p>{restaurante.descripcion}</p>
                  <p>{restaurante.direccion}</p>
                  <p>
                    Estado: <strong>{restaurante.estado}</strong>
                  </p>
                </div>

                <div className="restaurantes-actions">
                  <button
                    type="button"
                    className="button button--secondary"
                    onClick={() => editar(restaurante)}
                  >
                    Editar
                  </button>

                  {restaurante.estado !== 'INACTIVO' && (
                    <button
                      type="button"
                      className="button button--secondary"
                      onClick={() => desactivar(restaurante.id)}
                    >
                      Desactivar
                    </button>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}