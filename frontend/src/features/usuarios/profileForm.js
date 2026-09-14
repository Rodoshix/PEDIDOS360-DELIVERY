export const PROFILE_FIELDS = Object.freeze([
  Object.freeze({ name: 'nombre', label: 'Nombre', maxLength: 100, required: true, type: 'text', autoComplete: 'given-name' }),
  Object.freeze({ name: 'apellido', label: 'Apellido', maxLength: 100, required: true, type: 'text', autoComplete: 'family-name' }),
  Object.freeze({ name: 'email', label: 'Email de contacto', maxLength: 254, required: true, type: 'email', autoComplete: 'email' }),
  Object.freeze({ name: 'telefono', label: 'Teléfono (opcional)', maxLength: 30, required: false, type: 'tel', autoComplete: 'tel' }),
])

// Lista explícita: nunca copiar id, roles, estado, auditoría o identidad al formulario.
export function createProfileDraft(profile) {
  return Object.fromEntries(PROFILE_FIELDS.map(({ name }) => [name, typeof profile?.[name] === 'string' ? profile[name] : '']))
}

export function normalizeProfileDraft(draft) {
  const fields = createProfileDraft(draft)
  return {
    nombre: fields.nombre.trim(),
    apellido: fields.apellido.trim(),
    email: fields.email.trim().toLowerCase(),
    telefono: fields.telefono.trim() || null,
  }
}

export function validateProfileDraft(draft) {
  const values = normalizeProfileDraft(draft)
  const errors = {}
  for (const { name, label, maxLength, required } of PROFILE_FIELDS) {
    const value = values[name]
    if (required && !value) errors[name] = `${label} es obligatorio.`
    else if (value?.length > maxLength) errors[name] = `${label} admite hasta ${maxLength} caracteres.`
  }
  // Comprobación básica de interfaz; no sustituye @Email ni las reglas del servidor.
  if (!errors.email && !/^[^\s@]+@[^\s@]+$/u.test(values.email)) {
    errors.email = 'Ingresa un email válido, por ejemplo alex@example.test.'
  }
  return errors
}

export function hasProfileChanges(draft, initialProfile) {
  const initial = createProfileDraft(initialProfile)
  return PROFILE_FIELDS.some(({ name }) => draft[name] !== initial[name])
}
