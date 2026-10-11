import { useRef, useState } from 'react'
import { Field, Select } from '../../components/ui/Field.jsx'
import Button from '../../components/ui/Button.jsx'
import { validateCatalogDraft } from './catalogAdminService.js'

export default function CatalogAdminForm({ kind, initial, restaurants, saving, onSave, onCancel, onRefresh, onAuthorize, onLogin, error }) {
  const [draft, setDraft] = useState(() => initial || (kind === 'restaurantes'
    ? { nombre: '', descripcion: '', direccion: '', estado: 'ABIERTO' }
    : { nombre: '', descripcion: '', restauranteId: '', precio: '', categoria: '', disponible: true }))
  const [errors, setErrors] = useState({}), summary = useRef(null)
  const change = field => event => setDraft(current => ({ ...current, [field]: event.target.value }))
  const field = (name, label, max, required = false) => <Field label={label} name={name} value={draft[name] ?? ''} maxLength={max}
    required={required} error={errors[name]} onChange={change(name)} disabled={saving} />
  return <form className="admin-form" noValidate onSubmit={event => {
    event.preventDefault(); if (saving) return
    const found = validateCatalogDraft(kind, draft); setErrors(found)
    if (Object.keys(found).length) { queueMicrotask(() => summary.current?.focus()); return }
    void onSave(draft)
  }}>
    {(Object.keys(errors).length > 0 || error) && <div ref={summary} className="ui-alert ui-alert--danger" role="alert" tabIndex={-1}>
      {error?.message || 'Revisa los campos indicados antes de guardar.'}
      {error && <Button variant="secondary" disabled={saving} onClick={onRefresh}>Actualizar antes de repetir</Button>}
      {error?.code === 'INTERACTION_REQUIRED' && <><p>Conserva una copia del borrador antes de continuar: Microsoft puede sacarte de esta vista.</p><Button onClick={onAuthorize}>Continuar con Microsoft</Button></>}
      {error?.code === 'UNAUTHORIZED' && <><p>Conserva una copia del borrador antes de iniciar sesión nuevamente.</p><Button onClick={onLogin}>Iniciar sesión con Microsoft</Button></>}
    </div>}
    {field('nombre', 'Nombre', 120, true)}
    {field('descripcion', 'Descripción (opcional)', 500)}
    {kind === 'restaurantes' ? <>{field('direccion', 'Dirección (opcional)', 255)}
      <Field as={Select} label="Estado" value={draft.estado} required onChange={change('estado')} disabled={saving} error={errors.estado}>
        <option value="ABIERTO">Abierto</option><option value="CERRADO">Cerrado</option><option value="INACTIVO">Inactivo</option>
      </Field></> : <>
      <Field as={Select} label="Restaurante" value={draft.restauranteId} required disabled={saving} error={errors.restauranteId} onChange={change('restauranteId')}>
        <option value="">Selecciona un restaurante</option>{restaurants.map(row => <option key={row.id} value={row.id}>{row.nombre}</option>)}
      </Field>
      <Field label="Precio (CLP)" value={draft.precio} inputMode="decimal" required disabled={saving} onChange={change('precio')} error={errors.precio} hint="Mayor que cero; hasta dos decimales con punto. Ejemplo: 6990.50." />
      {field('categoria', 'Categoría', 80, true)}
      <Field as={Select} label="Disponibilidad" value={String(draft.disponible)} disabled={saving}
        onChange={event => setDraft(current => ({ ...current, disponible: event.target.value === 'true' }))}>
        <option value="true">Disponible</option><option value="false">No disponible</option>
      </Field>
    </>}
    <div className="admin-actions"><Button type="submit" loading={saving} disabled={!!error}>Guardar {kind === 'restaurantes' ? 'restaurante' : 'producto'}</Button>
      <Button variant="secondary" disabled={saving} onClick={onCancel}>Cancelar</Button></div>
  </form>
}
