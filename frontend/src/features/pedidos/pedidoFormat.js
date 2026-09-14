// Formateadores compartidos de Pedidos.
const fechaFmt = new Intl.DateTimeFormat('es-CL', { dateStyle: 'medium', timeStyle: 'short' })
const clpFmt = new Intl.NumberFormat('es-CL', { style: 'currency', currency: 'CLP', maximumFractionDigits: 0 })

export const formatFecha = value => fechaFmt.format(new Date(value))
export const formatClp = amount => clpFmt.format(amount)
