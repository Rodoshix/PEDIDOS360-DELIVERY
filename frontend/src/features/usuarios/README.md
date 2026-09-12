# Mi cuenta real — issue #42

Bloque 1: `AccountPage` monta `RealProfilePanel` con una clave por identidad
Microsoft. Se reutilizan `createProfileController` y el cliente HTTP autenticado.
El adaptador solicita exclusivamente `GET /usuarios/me` al BFF, con AbortSignal.
Solo un 404 controlado representa ausencia. Un 200 malformado, un 401/403 o un
error de red nunca habilitan el formulario de creación como si faltara el perfil.

La pantalla muestra carga, perfil real, error/reintento o formulario vacío.
El formulario no infiere datos desde la cuenta Microsoft. Su botón de guardado
está deshabilitado; el adaptador rechaza escrituras en este bloque. El borrador
es local al componente y no persiste. Cambiar de cuenta/desmontar cancela la
consulta y el controlador ignora respuestas tardías.

Los paneles/adaptadores demo siguen disponibles en `npm run preview:profile`,
separados de Mi cuenta y excluidos del bundle de producción. No se usa el demo
como respaldo ante errores de la API.

Verificación: `npm test`, `npm run lint`, `npm run build`. Prueba manual local
2026-09-12: con Entra real y BFF/Usuarios activos, la cuenta sin perfil en la base
temporal mostró el formulario tras 404, con guardado deshabilitado. No se
crearon perfiles ni se copiaron tokens. POST/PUT y prueba de persistencia desde
la UI quedan para bloques 2 y 3. El build advierte del chunk principal >500 kB.
