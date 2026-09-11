// Ejemplo explícito para desarrollo; no representa al usuario de MSAL.
// Copia nueva por llamada, sin almacenamiento ni transporte HTTP.
export function createExampleProfile() {
  return {
    id: 1,
    nombre: 'Alex',
    apellido: 'Ejemplo',
    email: 'alex@example.test',
    telefono: null,
    activo: true,
    creadoEn: '2026-09-10T12:00:00Z',
    actualizadoEn: '2026-09-10T12:00:00Z',
  }
}
