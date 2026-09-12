import { useEffect, useId, useRef, useState } from 'react'
import { PROFILE_FIELDS, createProfileDraft, hasProfileChanges, normalizeProfileDraft, validateProfileDraft } from './profileForm.js'

export default function ProfileForm({ initialProfile = null, onApply, onCancel, saving = false, submitError = null, mode = 'demo', saveEnabled = true }) {
  const [draft, setDraft] = useState(() => createProfileDraft(initialProfile))
  const [errors, setErrors] = useState({})
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  const errorSummary = useRef(null)
  const saveError = useRef(null)
  const discardPrompt = useRef(null)
  const cancelButton = useRef(null)
  const firstField = useRef(null)
  const wasConfirming = useRef(false)
  const id = useId()
  const dirty = hasProfileChanges(draft, initialProfile)
  const hasErrors = Object.keys(errors).length > 0

  useEffect(() => { firstField.current?.focus() }, [])
  useEffect(() => { if (submitError) saveError.current?.focus() }, [submitError])

  useEffect(() => {
    if (Object.keys(errors).length) errorSummary.current?.focus()
  }, [errors])

  useEffect(() => {
    if (confirmDiscard) {
      wasConfirming.current = true
      discardPrompt.current?.focus()
    } else if (wasConfirming.current) {
      cancelButton.current?.focus()
      wasConfirming.current = false
    }
  }, [confirmDiscard])

  useEffect(() => {
    if (!dirty) return
    const warn = event => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  function submit(event) {
    event.preventDefault()
    if (confirmDiscard || saving || !saveEnabled) return
    const nextErrors = validateProfileDraft(draft)
    setErrors(nextErrors)
    if (Object.keys(nextErrors).length) return
    onApply(normalizeProfileDraft(draft))
  }

  function cancel() {
    if (saving) return
    if (dirty) setConfirmDiscard(true)
    else onCancel()
  }

  return (
    <form className="profile-form" onSubmit={submit} noValidate aria-labelledby={`${id}-heading`} aria-busy={saving}>
      <h3 id={`${id}-heading`}>{initialProfile ? 'Editar perfil' : 'Crear perfil'}{mode === 'demo' ? ' de prueba' : ''}</h3>
      <p id={`${id}-help`} className="account-note">{mode === 'demo' ? 'Usa datos ficticios. Aplicar actualiza solo esta vista; no guarda en Usuarios ni cambia tu cuenta Microsoft.' : 'Datos de contacto del perfil. El guardado todavía está deshabilitado; el borrador no se envía a Usuarios.'}</p>
      {hasErrors && (
        <div ref={errorSummary} tabIndex={-1} className="profile-form__error-summary" role="alert">
          <p>Revisa los campos indicados antes de continuar.</p>
          <ul>{PROFILE_FIELDS.filter(({ name }) => errors[name]).map(({ name, label }) => (
            <li key={name}><a href={`#${id}-${name}`}>{label}: {errors[name]}</a></li>
          ))}</ul>
        </div>
      )}
      {submitError && <div ref={saveError} tabIndex={-1} className="profile-form__error-summary" role="alert">{submitError.message}</div>}
      {saving && <p role="status">Guardando ejemplo… No cierres esta vista mientras termina la simulación.</p>}
      <fieldset disabled={confirmDiscard || saving} aria-describedby={`${id}-help`}>
        <legend className="profile-form__legend">Datos de contacto</legend>
        <div className="profile-form__fields">
          {PROFILE_FIELDS.map(field => (
            <div className="profile-form__field" key={field.name}>
              <label htmlFor={`${id}-${field.name}`}>{field.label}{field.required && <span aria-hidden="true"> *</span>}</label>
              <input id={`${id}-${field.name}`} name={field.name} type={field.type} autoComplete={field.autoComplete}
                ref={field.name === 'nombre' ? firstField : undefined}
                required={field.required} maxLength={field.maxLength} value={draft[field.name]}
                aria-invalid={errors[field.name] ? true : undefined}
                aria-describedby={`${id}-${field.name}-hint${errors[field.name] ? ` ${id}-${field.name}-error` : ''}`}
                onChange={event => setDraft({ ...draft, [field.name]: event.target.value })} />
              <small id={`${id}-${field.name}-hint`}>{field.required ? 'Obligatorio' : 'Puedes dejarlo vacío'}. Hasta {field.maxLength} caracteres.</small>
              {errors[field.name] && <p className="profile-form__field-error" id={`${id}-${field.name}-error`}>{errors[field.name]}</p>}
            </div>
          ))}
        </div>
      </fieldset>
      <p className="account-note" role="status">{dirty ? 'Tienes cambios sin aplicar.' : 'Sin cambios pendientes.'}</p>
      <p className="account-note">El borrador se pierde al salir de Mi cuenta o cerrar sesión.</p>
      {confirmDiscard && (
        <div ref={discardPrompt} tabIndex={-1} className="profile-form__discard" role="group" aria-labelledby={`${id}-discard-heading`}>
          <h3 id={`${id}-discard-heading`}>¿Descartar los cambios?</h3>
          <p>Se perderá el borrador. El perfil anterior no se modificará.</p>
          <div className="account-actions">
            <button type="button" className="button button--secondary session-controls__button" onClick={() => {
              setConfirmDiscard(false)
            }}>Seguir editando</button>
            <button type="button" className="button button--primary session-controls__button" onClick={onCancel}>Descartar cambios</button>
          </div>
        </div>
      )}
      <div className="account-actions">
        <button type="submit" className="button button--primary session-controls__button" disabled={!saveEnabled || saving || confirmDiscard || (Boolean(initialProfile) && !dirty)}>{!saveEnabled ? 'Guardado pendiente de integración' : saving ? 'Guardando ejemplo…' : 'Aplicar al ejemplo'}</button>
        <button ref={cancelButton} type="button" className="button button--secondary session-controls__button" onClick={cancel} disabled={saving || confirmDiscard}>Cancelar</button>
      </div>
    </form>
  )
}
