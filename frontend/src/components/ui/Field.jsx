import { useId } from 'react'

export function Input({ className = '', ...props }) {
  return <input {...props} className={`ui-input ${className}`} />
}
export function Select({ className = '', children, ...props }) {
  return <select {...props} className={`ui-input ${className}`}>{children}</select>
}
// El padre conserva valores, handlers y validación.
export function Field({ label, hint, error, id: providedId, as: Control = Input, ...props }) {
  const generatedId = useId()
  const id = providedId || generatedId
  const describedBy = [props['aria-describedby'], hint && `${id}-hint`, error && `${id}-error`].filter(Boolean).join(' ') || undefined
  return <div className="ui-field">
    <label htmlFor={id}>{label}{props.required && <span aria-hidden="true"> *</span>}</label>
    <Control {...props} id={id} aria-invalid={error ? true : props['aria-invalid']} aria-describedby={describedBy} />
    {hint && <small id={`${id}-hint`} className="ui-field__hint">{hint}</small>}
    {error && <p id={`${id}-error`} className="ui-field__error">{error}</p>}
  </div>
}
