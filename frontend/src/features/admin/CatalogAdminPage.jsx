import { useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router'
import { useAuthSession } from '../../auth/useAuthSession.js'
import { createCatalogAdminController } from './catalogAdminService.js'
import { createCatalogAdminHttpAdapter, adminFailure } from './catalogAdminHttpAdapter.js'
import CatalogAdminForm from './CatalogAdminForm.jsx'
import Button from '../../components/ui/Button.jsx'
import { Field, Select } from '../../components/ui/Field.jsx'
import Table from '../../components/ui/Table.jsx'
import Badge from '../../components/ui/Badge.jsx'
import Dialog from '../../components/ui/Dialog.jsx'
import { Alert, EmptyState } from '../../components/feedback/Feedback.jsx'
import LoadingState from '../../components/feedback/LoadingState.jsx'
import Toast from '../../components/feedback/Toast.jsx'
import './admin.css'

export default function CatalogAdminPage({ kind }) {
  const { account } = useAuthSession()
  const key = JSON.stringify([account.tenantId, account.homeAccountId, account.localAccountId, kind])
  return <AdminPanel key={key} kind={kind} />
}
function AdminPanel({ kind }) {
  const [state, setState] = useState({ status: 'loading', rows: [], restaurants: [], error: null })
  const [filter, setFilter] = useState(''), [search, setSearch] = useState(''), [dialog, setDialog] = useState(null)
  const [discard, setDiscard] = useState(false), [message, setMessage] = useState('')
  const controller = useRef(null), adapter = useRef(null), detailAbort = useRef(null), heading = useRef(null), origin = useRef(null), writePending = useRef(false)
  const session = useAuthSession(), location = useLocation()
  const busy = ['loading', 'saving'].includes(state.status), saving = state.status === 'saving'
  useEffect(() => {
    let active = true, current
    import('../../services/httpClient.js').then(({ default: client }) => {
      if (!active) return
      adapter.current = createCatalogAdminHttpAdapter(client, kind)
      current = createCatalogAdminController(adapter.current); controller.current = current
      current.subscribe(setState); void current.load()
    }).catch(error => { if (active) setState(current => ({ ...current, status: 'error', error: adminFailure(error) })) })
    return () => { active = false; current?.dispose(); detailAbort.current?.abort() }
  }, [kind])
  async function openDetail(row, mode, button) {
    origin.current = button; detailAbort.current?.abort()
    const abort = new AbortController(); detailAbort.current = abort
    setDialog({ mode: 'loading' }); setDiscard(false)
    try {
      const data = await adapter.current.detail(row.id, abort.signal)
      if (!abort.signal.aborted) setDialog({ mode, row: data })
    } catch (error) { if (!abort.signal.aborted) setDialog({ mode: 'error', error: adminFailure(error) }) }
  }
  function closeDialog() {
    if (writePending.current || saving) return
    if (dialog?.mode === 'form') { setDiscard(true); return }
    detailAbort.current?.abort(); setDialog(null)
  }
  async function apply(command) {
    if (writePending.current) return
    writePending.current = true
    try {
      if (await controller.current?.write(command, filter)) { setDialog(null); setDiscard(false); setMessage('Cambio guardado en el catálogo.'); heading.current?.focus() }
    } finally { writePending.current = false }
  }
  const label = kind === 'restaurantes' ? 'Restaurantes' : 'Productos', singular = kind === 'restaurantes' ? 'restaurante' : 'producto'
  const rows = state.rows.filter(row => row.nombre.toLocaleLowerCase().includes(search.toLocaleLowerCase()))
  const status = row => kind === 'restaurantes' ? ({ ABIERTO: 'Abierto', CERRADO: 'Cerrado', INACTIVO: 'Inactivo' }[row.estado]) : row.disponible ? 'Disponible' : 'No disponible'
  const good = row => kind === 'restaurantes' ? row.estado === 'ABIERTO' : row.disponible
  return <section className="container admin-page" aria-busy={busy}>
    <div className="admin-heading"><div><h1 ref={heading} tabIndex={-1}>Administrar {label.toLowerCase()}</h1><p>Gestiona el catálogo que consultan tus clientes.</p></div>
      <Button disabled={busy || !!state.error} onClick={event => { origin.current = event.currentTarget; setDiscard(false); setDialog({ mode: 'form' }) }}>Crear {singular}</Button></div>
    <nav className="admin-tabs" aria-label="Administración del catálogo"><Link to="/admin/restaurantes" aria-current={kind === 'restaurantes' ? 'page' : undefined}>Restaurantes</Link><Link to="/admin/productos" aria-current={kind === 'productos' ? 'page' : undefined}>Productos</Link></nav>
    {message && <Toast message={message} onDismiss={() => setMessage('')} returnFocusRef={heading} />}
    {state.error && <Alert><p>{state.error.message}</p><p>Si un cambio no pudo confirmarse, actualiza antes de repetirlo.</p>
      <Button variant="secondary" disabled={busy} onClick={() => void controller.current?.load(filter)}>Actualizar listado</Button>
      {state.error.code === 'INTERACTION_REQUIRED' && <Button onClick={() => session.authorizeApi(location.pathname)}>Continuar con Microsoft</Button>}
      {state.error.code === 'UNAUTHORIZED' && <Button onClick={() => session.login(location.pathname)}>Iniciar sesión con Microsoft</Button>}</Alert>}
    <div className="admin-toolbar"><Field label={`Buscar ${label.toLowerCase()}`} value={search} onChange={event => setSearch(event.target.value)} />
      {kind === 'productos' && <Field label="Filtrar por restaurante" as={Select} value={filter} disabled={busy || dialog?.mode === 'form'} onChange={event => { setFilter(event.target.value); void controller.current?.load(event.target.value) }}>
        <option value="">Todos los restaurantes</option>{state.restaurants.map(row => <option key={row.id} value={row.id}>{row.nombre}</option>)}</Field>}
      <Button variant="secondary" disabled={busy || dialog?.mode === 'form'} onClick={() => void controller.current?.load(filter)}>Actualizar</Button></div>
    {busy ? <LoadingState label={saving ? 'Guardando cambio…' : `Consultando ${label.toLowerCase()}…`} /> : !state.error && (rows.length === 0
      ? <EmptyState title={search ? 'Sin coincidencias' : `No hay ${label.toLowerCase()}`}><p>{search ? 'Prueba con otro nombre.' : filter ? 'Este restaurante no tiene productos registrados.' : `Crea el primer ${singular} para comenzar.`}</p></EmptyState>
      : <Table caption={`Listado de ${label.toLowerCase()}`}><thead><tr><th scope="col">Nombre</th>{kind === 'productos' && <><th scope="col">Restaurante</th><th scope="col">Precio (CLP)</th></>}<th scope="col">Estado</th><th scope="col">Acciones</th></tr></thead>
        <tbody>{rows.map(row => <tr key={row.id}><th scope="row">{row.nombre}</th>{kind === 'productos' && <><td>{state.restaurants.find(r => r.id === row.restauranteId)?.nombre || `Restaurante #${row.restauranteId}`}</td><td className="admin-price">{Number(row.precio).toLocaleString('es-CL', { style: 'currency', currency: 'CLP', maximumFractionDigits: 2 })}</td></>}
          <td><Badge tone={good(row) ? 'success' : 'neutral'}>{status(row)}</Badge></td><td><div className="admin-row-actions">
            <Button variant="ghost" size="small" aria-label={`Ver detalle de ${row.nombre}`} onClick={event => void openDetail(row, 'detail', event.currentTarget)}>Detalle</Button>
            <Button variant="secondary" size="small" aria-label={`Editar ${row.nombre}`} onClick={event => void openDetail(row, 'form', event.currentTarget)}>Editar</Button>
            {(kind === 'productos' || row.estado !== 'INACTIVO') && <Button variant="ghost" size="small" aria-label={`${kind === 'restaurantes' ? 'Desactivar' : 'Cambiar disponibilidad de'} ${row.nombre}`} onClick={event => { origin.current = event.currentTarget; setDialog({ mode: 'confirm', row }) }}>{kind === 'restaurantes' ? 'Desactivar' : row.disponible ? 'No disponible' : 'Habilitar'}</Button>}
          </div></td></tr>)}</tbody></Table>)}
    <Dialog open={!!dialog} onOpenChange={open => { if (!open) closeDialog() }} dismissDisabled={busy} returnFocusRef={origin}
      title={dialog?.mode === 'form' ? `${dialog.row ? 'Editar' : 'Crear'} ${singular}` : dialog?.mode === 'confirm' ? 'Confirmar cambio de estado' : `Detalle del ${singular}`}
      description={dialog?.mode === 'confirm' ? 'Este cambio modifica el catálogo real.' : dialog?.mode === 'form' ? 'Guardar envía estos datos al catálogo de Pedidos360.' : 'Información guardada en Pedidos360.'} className="admin-dialog">
      {dialog?.mode === 'loading' && <LoadingState label="Consultando detalle…" />}
      {dialog?.mode === 'error' && <Alert>{dialog.error.message}</Alert>}
      {dialog?.mode === 'detail' && <dl className="admin-details">{Object.entries(dialog.row).map(([key, value]) => <div key={key}><dt>{({ id: 'ID', nombre: 'Nombre', descripcion: 'Descripción', direccion: 'Dirección', estado: 'Estado', restauranteId: 'Restaurante ID', precio: 'Precio (CLP)', categoria: 'Categoría', disponible: 'Disponible' })[key]}</dt><dd>{typeof value === 'boolean' ? value ? 'Sí' : 'No' : value || 'Sin registrar'}</dd></div>)}</dl>}
      {dialog?.mode === 'form' && <><CatalogAdminForm kind={kind} initial={dialog.row} restaurants={state.restaurants} saving={busy} error={state.error}
        onRefresh={() => void controller.current?.load(filter)}
        onAuthorize={() => session.authorizeApi(location.pathname)} onLogin={() => session.login(location.pathname)}
        onSave={draft => apply({ type: 'save', id: dialog.row?.id, draft })} onCancel={closeDialog} />
        {discard && <div className="ui-alert ui-alert--warning" role="alert"><p>¿Descartar el formulario? Los datos sin guardar se perderán.</p><div className="admin-actions"><Button variant="secondary" disabled={busy} onClick={() => setDiscard(false)}>Seguir editando</Button><Button variant="danger" disabled={busy} onClick={() => { setDialog(null); setDiscard(false) }}>Descartar formulario</Button></div></div>}</>}
      {dialog?.mode === 'confirm' && <><p>{kind === 'restaurantes' ? `Desactivar ${dialog.row.nombre}. Sus datos se conservarán.` : `Marcar ${dialog.row.nombre} como ${dialog.row.disponible ? 'no disponible' : 'disponible'}.`}</p>
        {state.error && <Alert>{state.error.message}<Button variant="secondary" disabled={busy} onClick={() => void controller.current?.load(filter)}>Actualizar antes de repetir</Button></Alert>}<div className="admin-actions"><Button variant={kind === 'restaurantes' ? 'danger' : 'primary'} loading={busy} disabled={!!state.error}
          onClick={() => void apply({ type: kind === 'restaurantes' ? 'deactivate' : 'availability', id: dialog.row.id, disponible: !dialog.row.disponible })}>Confirmar cambio</Button><Button variant="secondary" disabled={saving} onClick={closeDialog}>Cancelar</Button></div></>}
    </Dialog>
  </section>
}
