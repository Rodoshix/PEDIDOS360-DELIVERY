export default function Button({ children, variant = 'primary', size = 'normal', loading = false, disabled, className = '', type = 'button', ...props }) {
  return <button {...props} type={type} disabled={disabled || loading} aria-busy={loading || undefined}
    className={`ui-button ui-button--${variant} ui-button--${size} ${className}`}>{children}</button>
}
