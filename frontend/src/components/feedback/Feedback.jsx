export function Skeleton({ className = '', label = 'Cargando…' }) {
  return <div role="status" className={`ui-skeleton ${className}`}><span className="sr-only">{label}</span></div>
}
export function EmptyState({ title, children, action }) {
  return <div className="ui-empty"><h2>{title}</h2>{children && <div>{children}</div>}{action}</div>
}
export function Alert({ tone = 'danger', children, ...props }) {
  return <div role={tone === 'danger' ? 'alert' : 'status'} {...props} className={`ui-alert ui-alert--${tone}`}>{children}</div>
}
