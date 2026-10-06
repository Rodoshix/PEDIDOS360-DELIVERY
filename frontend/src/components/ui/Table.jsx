export default function Table({ caption, children, className = '', ...props }) {
  return <div className="ui-table-scroll" role="region" aria-label={caption || 'Tabla de datos'} tabIndex={0}>
    <table {...props} className={`ui-table ${className}`}>
      {caption && <caption>{caption}</caption>}{children}
    </table>
  </div>
}
