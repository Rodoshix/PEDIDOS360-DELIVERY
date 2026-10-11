export default function Card({ as: Component = 'section', className = '', children, ...props }) {
  return <Component {...props} className={`ui-card ${className}`}>{children}</Component>
}
