import { useEffect, useId, useRef, useState } from 'react'
import { parseQuantity } from './cartOperations.js'

export default function CartQuantityForm({ item, onChange, disabled = false, saving = false }) {
  const id = useId()
  const [draft, setDraft] = useState(String(item.cantidad))
  const [error, setError] = useState('')
  const errorRef = useRef(null)
  const inputRef = useRef(null)
  const submitting = useRef(false)
  useEffect(() => { if (error) errorRef.current?.focus() }, [error])
  async function submit(event) {
    event.preventDefault()
    if (disabled || submitting.current) return
    let quantity
    try { quantity = parseQuantity(draft) } catch {
      setError('La cantidad debe ser un entero entre 1 y 99.')
      errorRef.current?.focus()
      return
    }
    setError('')
    submitting.current = true
    try { if (await onChange(item.productoId, quantity) !== false) setDraft(String(quantity)) }
    finally { submitting.current = false }
  }
  return (
    <form onSubmit={submit} noValidate aria-label={`Cambiar cantidad de ${item.nombre}`} aria-busy={saving}>
      <fieldset disabled={disabled} className="cart-edit-fields">
        <legend className="cart-field-legend">Editar cantidad</legend>
        <label htmlFor={id}>Nueva cantidad de {item.nombre}</label>
        <div className="cart-quantity-actions">
          <input ref={inputRef} id={id} name="cantidad" inputMode="numeric" value={draft} onChange={event => setDraft(event.target.value)}
            required maxLength={3} aria-invalid={error ? true : undefined} aria-describedby={`${id}-hint${error ? ` ${id}-error` : ''}`} />
          <button type="submit" className="button button--secondary" disabled={draft === String(item.cantidad)}>Aplicar cantidad</button>
          {draft !== String(item.cantidad) && <button type="button" className="button button--secondary" onClick={() => {
            setDraft(String(item.cantidad)); setError(''); inputRef.current?.focus()
          }}>Restablecer cantidad</button>}
        </div>
        <small id={`${id}-hint`}>Entre 1 y 99 unidades. No se aplica hasta confirmar.</small>
      </fieldset>
      {error && <p id={`${id}-error`} ref={errorRef} tabIndex={-1} role="alert" className="cart-error">{error}</p>}
    </form>
  )
}
