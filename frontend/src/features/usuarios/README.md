# Mi cuenta real — issue #42

Bloques 1 y 2: `AccountPage` monta `RealProfilePanel` con una clave por identidad
Microsoft. Se reutilizan `createProfileController` y el cliente HTTP autenticado.
Para consultar, el adaptador solicita `GET /usuarios/me` al BFF, con AbortSignal.
Solo un 404 controlado representa ausencia. Un 200 malformado, un 401/403 o un
error de red nunca habilitan el formulario de creación como si faltara el perfil.

La pantalla muestra carga, perfil real, error/reintento o formulario vacío.
El formulario no infiere datos desde la cuenta Microsoft. El borrador es local
al componente y no persiste. Cambiar de cuenta/desmontar cancela la consulta y
el controlador ignora respuestas tardías.

Después de un 404, crear usa POST /usuarios y exige 201 con perfil válido. Editar
usa PUT /usuarios/{id}, exclusivamente con el ID obtenido del servicio, y exige
200 con ese mismo ID. Solo se envían nombre/apellido/email/teléfono normalizados;
no se envían IDs ni roles del borrador. El backend determina la identidad.
Guardar bloquea campos/botones y el controlador evita doble envío inmediato.
Ante errores se conserva el formulario y se muestran mensajes sanitizados.
No hay reintentos automáticos ni promesa de que un timeout implique rollback.
Para reconciliar un fallo incierto, conservar el borrador, cancelar su edición
y usar Actualizar consulta antes de volver a guardar. Una autorización que
redirige a Microsoft puede perder el borrador, como indica la interfaz.

Los paneles/adaptadores demo siguen disponibles en `npm run preview:profile`,
separados de Mi cuenta y excluidos del bundle de producción. No se usa el demo
como respaldo ante errores de la API.

Verificación: `npm test`, `npm run lint`, `npm run build`. Prueba manual local
2026-09-12 (bloque 1): con Entra real y BFF/Usuarios activos, la cuenta sin perfil
en la base temporal mostró el formulario tras 404. Bloque 2: 184 tests frontend
aprobados y lint/build correctos; creación/edición cubiertas con cliente inyectado,
incluyendo estados HTTP, datos inválidos, doble envío y cancelación.
El build advierte del chunk principal >500 kB; no impide compilar.

## Evidencia manual del bloque 3

Prueba realizada el 2026-09-12 en el navegador integrado, con sesión Entra real,
frontend local, BFF y Usuarios activos, y PostgreSQL temporal. Recorrido:

1. La consulta inicial sin perfil mostró el formulario de creación.
2. Se creó un perfil ficticio: nombre `Prueba`, apellido `Integracion`, email
   `perfil.integracion@example.test` y teléfono vacío. La UI confirmó el guardado.
3. Se editó el nombre a `Prueba Editada`; la UI mostró el perfil actualizado.
4. Una consulta directa de PostgreSQL confirmó un único registro con esos datos.
5. Se recargó Mi cuenta y el nombre editado permaneció, obtenido de Usuarios.

No se modificaron datos de la cuenta Microsoft ni se copiaron tokens o secretos.
La base de prueba es desechable: esta evidencia demuestra persistencia entre
consultas y recargas mientras existe esa base, no un despliegue en AWS ni
durabilidad tras eliminar el contenedor temporal. Para repetir, usar otra base
de prueba vacía y una cuenta autorizada; no borrar perfiles de una base compartida.
Los escenarios de error, cancelación y doble envío se cubren en los tests
automatizados; el recorrido manual anterior corresponde al caso exitoso.
