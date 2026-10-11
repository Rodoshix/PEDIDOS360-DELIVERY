import * as Primitive from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import Button from './Button.jsx'

export default function Dialog({ open, onOpenChange, title, description, children, trigger, className = '', contentRef, returnFocusRef, dismissDisabled = false }) {
  return <Primitive.Root open={open} onOpenChange={onOpenChange}>
    {trigger && <Primitive.Trigger asChild>{trigger}</Primitive.Trigger>}
    <Primitive.Portal>
      <Primitive.Overlay className="ui-dialog-overlay" />
      <Primitive.Content ref={contentRef} className={`ui-dialog ${className}`}
        onEscapeKeyDown={event => { if (dismissDisabled) event.preventDefault() }}
        onPointerDownOutside={event => { if (dismissDisabled) event.preventDefault() }}
        onCloseAutoFocus={event => { if (returnFocusRef?.current) { event.preventDefault(); returnFocusRef.current.focus() } }}>
        <div className="ui-dialog-heading">
          <Primitive.Title>{title}</Primitive.Title>
          <Primitive.Close asChild><Button variant="ghost" size="icon" disabled={dismissDisabled} aria-label="Cerrar ventana"><X size={20} aria-hidden="true" /></Button></Primitive.Close>
        </div>
        <Primitive.Description className="ui-dialog-description">{description}</Primitive.Description>
        {children}
      </Primitive.Content>
    </Primitive.Portal>
  </Primitive.Root>
}
