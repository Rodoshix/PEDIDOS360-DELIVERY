export default function Badge({ tone = 'neutral', children, className = '', ...props }) {
  return <span {...props} className={`ui-badge ui-badge--${tone} ${className}`}>{children}</span>
}
