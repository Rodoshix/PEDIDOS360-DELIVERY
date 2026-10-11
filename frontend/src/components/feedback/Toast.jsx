import { X } from 'lucide-react'
import Button from '../ui/Button.jsx'

// Confirmaciones de presentación; los errores críticos permanecen junto al formulario.
export default function Toast({ message, onDismiss, ref, returnFocusRef }) {
  return <div className="ui-toast" ref={ref} tabIndex={-1}>
    <p role="status">{message}</p>
    <Button variant="ghost" size="icon" aria-label="Cerrar notificación" onClick={() => {
      onDismiss()
      const focusTarget = returnFocusRef?.current || document.getElementById('contenido-principal')
      focusTarget?.focus()
    }}><X size={18} aria-hidden="true" /></Button>
  </div>
}
