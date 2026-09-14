const clp = new Intl.NumberFormat('es-CL', { style: 'currency', currency: 'CLP', maximumFractionDigits: 0 })

export function formatClp(amount) {
  if (!Number.isSafeInteger(amount) || amount < 0) throw new RangeError('El monto CLP debe ser un entero seguro no negativo.')
  return clp.format(amount)
}
