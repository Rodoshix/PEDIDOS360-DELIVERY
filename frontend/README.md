# Pedidos360 Delivery — Frontend

Base compartida con React, Vite, JavaScript, React Router y Axios. Incluye Inicio, página 404, layout, cliente HTTP y autenticación con Microsoft Entra ID (Issue #11, PR #18 integrado). El Issue #21 incorpora Perfil / Mi cuenta por bloques, sin integración real de servicios todavía.

Autenticación implementada: sesión MSAL, rutas privadas, retorno seguro y adquisición de access token conectada a Axios. El responsable confirmó el recorrido antes del cierre del Issue #11, después de migrar a su propio directorio el 10 de septiembre de 2026. Las pruebas automatizadas usan dobles de MSAL y un servidor HTTP local con credenciales ficticias, nunca tokens reales. La aceptación del token por BFF/servicios todavía no está implementada.

## Instalación y ejecución

Vite requiere Node.js `^20.19.0 || >=22.12.0`. Esta base se validó con Node.js `22.19.0` y npm `10.9.3`.

Desde la raíz del repositorio, en PowerShell:

```powershell
cd frontend
npm ci
if (-not (Test-Path .env.local)) {
  Copy-Item .env.example .env.local
}
# Completar las variables VITE_ENTRA_* antes de continuar.
npm run dev
```

Crear `.env.local` solo la primera vez; conservarlo si ya existe. Abrir `http://localhost:5173`, que coincide con la URI SPA registrada en Entra. Vite usa `strictPort`: si el puerto está ocupado, falla en vez de saltar a otro. `preview` usa ese mismo puerto; detén `dev` antes de iniciarlo. Las páginas iniciales no necesitan backend, pero requieren la configuración de Entra completa para arrancar.

## Variables de entorno

Editar `frontend/.env.local`:

```dotenv
VITE_API_BASE_URL=http://localhost:8080
VITE_ENTRA_CLIENT_ID=<id-de-aplicacion-de-pedidos360-frontend>
VITE_ENTRA_TENANT_ID=<id-del-directorio-del-equipo>
VITE_ENTRA_REDIRECT_URI=http://localhost:5173
VITE_ENTRA_API_SCOPE=api://<id-de-aplicacion-de-pedidos360-api>/access_as_user
```

- En local, indicar la dirección real del BFF Spring Boot. El puerto `8080` es un ejemplo; esta base no inicia ese servicio.
- En AWS, usar la URL pública de API Gateway, incluyendo su etapa o prefijo cuando corresponda.
- Reiniciar Vite después de cambiar la variable. En producción, configurarla antes de compilar; cambiarla requiere otro build.
- Si falta o está vacía, el cliente usa `/api` en el mismo origen del frontend. No hay proxy configurado en Vite: ese valor por sí solo no conecta al BFF.
- Cuando frontend y API tienen orígenes diferentes, el backend/API Gateway debe permitir el origen del frontend mediante CORS.

Las variables `VITE_*` se incorporan al código visible del navegador: no colocar contraseñas ni secretos. `.env.local` está ignorado por Git y `.env.example` sí se versiona.

### Configuración de Entra

- `VITE_ENTRA_CLIENT_ID`: ID de aplicación (cliente) del **frontend**, no su identificador de objeto.
- `VITE_ENTRA_TENANT_ID`: UUID del directorio donde están ambos registros. No usar `common` ni el ID de suscripción Azure.
- `VITE_ENTRA_REDIRECT_URI`: URI absoluta registrada como SPA, en el mismo origen desde el que abres React. HTTPS en despliegues; HTTP solo para pruebas con `localhost`. No admite consulta, fragmento ni credenciales.
- `VITE_ENTRA_API_SCOPE`: ámbito delegado completo de **la API**, distinto del ID del frontend. No usar `User.Read` de Microsoft Graph ni `.default`.
- El retorno después del logout usará la misma URI. La caché de MSAL se configura en `sessionStorage`; no registrar tokens ni datos personales en consola.
- Si una variable falta o es inválida, se muestra una pantalla explicativa y no se monta la aplicación con una identidad simulada. La validación de formato no comprueba por sí sola que los registros existan ni que el consentimiento sea correcto.

El directorio de desarrollo actual es **Rodrigo Saez**. El 10 de septiembre de 2026 se reemplazaron localmente los IDs del directorio prestado por los de este directorio; no se modificaron sus registros anteriores. Al configurar otra máquina, obtener los IDs de los registros actuales y completar `.env.local`, sin reutilizar los del compañero.

En el portal se configuraron frontend SPA, API con `access_as_user`, consentimiento concedido y rol CLIENTE asignado a la cuenta de Rodrigo. Los roles CLIENTE/ADMIN están habilitados para usuarios o grupos y pertenecen a la API; no se debe asumir que aparecen en el ID token del frontend. ADMIN no está asignado a la cuenta de prueba. La validación de tokens en BFF/servicios sigue pendiente.

Referencia: [inicialización de MSAL React](https://learn.microsoft.com/en-us/entra/msal/javascript/react/getting-started).

### Iniciar y cerrar sesión

1. Abre `http://localhost:5173` y pulsa **Iniciar sesión con Microsoft**. Se abre Microsoft en la misma pestaña; las credenciales y MFA se introducen allí, nunca en formularios propios ni en el repositorio.
2. Selecciona la cuenta del directorio configurado y completa el acceso. Al regresar, el encabezado debe mostrar su nombre y cuenta sin recargar manualmente.
3. Recarga: se conserva la cuenta de la caché de esa pestaña. Si hay varias cuentas y ninguna activa, se pide una selección mediante el botón de login, sin elegir arbitrariamente la primera.
4. Pulsa **Cerrar sesión** y completa la salida de Microsoft. Debe regresar al inicio con el botón de login. No basta con cerrar la pestaña para garantizar la salida de Microsoft.
5. Si cancelas o falla el acceso, se muestra un mensaje controlado y puedes intentarlo otra vez. No se reintenta automáticamente y los botones se bloquean durante una operación.

El retorno se procesa con `handleRedirectPromise` antes de montar las rutas. Se usan `loginRedirect`, `logoutRedirect` y, solo tras pulsar un botón, `acquireTokenRedirect`, con la URI raíz ya registrada. No se usan popup, `ssoSilent` ni renovación mediante iframe; no hay que agregar otra URI en Entra para este bloque.

Login solicita `openid` y `profile`. Para la API se solicita exclusivamente el ámbito completo `access_as_user` configurado. Mostrar una cuenta no acredita autorización ni rol ADMIN. Las verificaciones del directorio en la UI tampoco sustituyen la validación criptográfica de tokens en el backend.

### Rutas privadas y regreso después del login

- Inicio y 404 siguen siendo públicos. `/mi-cuenta` presenta la sesión y la consulta/edición de un perfil de prueba; la conexión real sigue pendiente.
- `RequireSession` no monta el contenido privado mientras MSAL está ocupado ni cuando falta una cuenta. Muestra una espera o una invitación a entrar; nunca inicia redirecciones automáticamente.
- Tanto el botón del encabezado como **Entrar para continuar** conservan ruta, consulta y fragmento. El destino se guarda en `sessionStorage`; solo una clave aleatoria se envía en `state`, siguiendo la [recomendación de Microsoft para estado personalizado](https://learn.microsoft.com/en-us/entra/msal/javascript/browser/mip-pass-custom-state).
- Tras una respuesta válida del directorio se consume la clave una sola vez y se reemplaza la URL antes de montar las rutas. No se hace una segunda petición de página. La URI SPA de Entra sigue siendo `http://localhost:5173`; no hay que registrar cada ruta privada.
- Se rechazan URLs externas, direcciones ambiguas, caracteres de control, respuestas OAuth y destinos malformados. Sin destino válido, con clave distinta o transcurridos 15 minutos se vuelve a `/`. Cancelación, fallo, logout y arranque sin respuesta descartan el destino pendiente; no quedan reintentos automáticos.
- Esta protección es de navegación del frontend. El backend debe validar tokens y permisos por separado.

Recorrido de regresión de autenticación (repetir al cambiar configuración o navegación):

1. Sin sesión, abrir `http://localhost:5173/mi-cuenta?tab=datos#contacto`: debe pedir iniciar sesión sin mostrar el contenido privado.
2. Pulsar **Entrar para continuar** y completar Microsoft. Debe regresar a esa misma dirección, conservando `?tab=datos#contacto`, y mostrar **Mi cuenta**. Esos parámetros sirven para verificar el retorno; no activan un formulario.
3. Recargar la página: debe seguir mostrando la vista privada con la sesión activa.
4. Cerrar sesión y volver a abrir `/mi-cuenta`: debe pedir entrar otra vez. Inicio y una ruta inexistente deben seguir funcionando sin sesión.

## Acceso a la API y comandos

### Acceso a la API

El cliente compartido `src/services/httpClient.js` requiere sesión por defecto y adjunta `Authorization: Bearer <access token>`. No usa el ID token, ni un token de Graph. MSAL se configura antes de montar la aplicación; cada petición obtiene un token mediante `acquireTokenSilent` y las adquisiciones concurrentes comparten la misma operación. No se crea otra caché de tokens.

Se usa `CacheLookupPolicy.AccessTokenAndRefreshToken`: puede reutilizar un access token o renovarlo con el refresh token, pero no recurre a un iframe si hace falta otra interacción. En ese caso devuelve `INTERACTION_REQUIRED`; **no redirige ni reenvía peticiones automáticamente**. El usuario puede pulsar **Continuar con Microsoft** y, al regresar, volver a intentar la operación. Referencia: [políticas de caché de MSAL](https://learn.microsoft.com/en-us/entra/msal/javascript/browser/token-lifetimes).

Reglas del cliente:

- Destino limitado al origen y prefijo de `VITE_API_BASE_URL`. Ejemplo: una base `https://api.example.test/prod` permite `/prod/usuarios`, pero no `/production`, otro puerto u otro origen. Se rechazan rutas ambiguas y credenciales en URL. Los servicios deben usar el cliente compartido; no modificar sus interceptores, adaptador ni transformaciones para saltarse estas reglas.
- Transporte Fetch con redirecciones bloqueadas y sin cookies. Un 301/302 se trata como fallo de conexión; configurar directamente la URL final del backend. CORS debe permitir el origen del frontend y la cabecera `Authorization`.
- La opción `{ authRequired: false }` sirve solo para endpoints públicos acordados con el backend. No adquiere token y elimina Authorization. Sigue limitada al mismo destino; no convierte un endpoint privado en público.
- Los errores son `ApiAccessError` con `message`, `code` y, cuando corresponde, `status`. `API_UNAUTHORIZED` (401), `API_FORBIDDEN` (403), `API_TIMEOUT` y `API_NETWORK` no provocan reintentos ni logout automático. Una cancelación conserva `axios.isCancel(error)`.
- Las respuestas conservan `data`, `status`, `statusText` y `headers`, pero no `config` ni `request`. Los errores no exponen el cuerpo original, la petición, tokens ni causas crudas. No registrar cabeceras ni respuestas de MSAL.
- Cada pantalla debe mostrar `error.message`; si recibe `INTERACTION_REQUIRED`, puede usar `authorizeApi(destinoInterno)` de `useAuthSession()` desde una acción explícita. Tras un 401/403 debe mostrar el error y revisar la configuración o permisos, no iniciar un bucle de login.

Prueba manual del permiso de API, sin enviar credenciales al backend (el responsable reportó funcionamiento después de la migración; repetir al cambiar directorio o permisos):

1. Iniciar sesión y abrir **Mi cuenta**.
2. Abrir **Diagnóstico de acceso a la API** dentro de **Tu acceso** y pulsar **Comprobar permiso de API**. Solo obtiene y descarta el token en memoria: no lo muestra, no lo copia y no llama al backend.
3. Si Microsoft requiere interacción, pulsar **Continuar con Microsoft**, completar el acceso y volver a pulsar **Comprobar permiso de API** al regresar.
4. Debe aparecer que Microsoft entregó un token para nuestra API. Esto **no prueba** que el BFF o los servicios lo acepten. La prueba extremo a extremo queda pendiente de implementar su validación de firma, issuer, audience, scopes y roles.

### Comandos locales

Ejecutar desde `frontend/`:

| Comando | Uso |
| --- | --- |
| `npm ci` | Instalar las versiones de `package-lock.json`. |
| `npm run dev` | Iniciar el servidor de desarrollo. |
| `npm run lint` | Revisar el código con Oxlint. |
| `npm test` | Probar configuración, sesión, rutas, tokens simulados y cliente HTTP local, sin Azure ni credenciales reales. |
| `npm run build` | Generar la aplicación en `dist/`. |
| `npm run preview` | Revisar localmente el resultado de build. |
| `npm run preview:profile` | Banco visual aislado de Perfil, con sesión ficticia y puerto aleatorio de loopback; no usa Microsoft ni backend. |
| `npm run preview:cart` | El mismo banco visual, iniciando en Carrito con sesión ficticia; sin Microsoft ni backend. |

Para agregar una dependencia, usar `npm install nombre-paquete` y guardar juntos `package.json` y `package-lock.json`. No versionar `node_modules/` ni `dist/`.

## Estructura compartida

| Carpeta de `src/` | Responsabilidad |
| --- | --- |
| `features/` | Pantallas y servicios por dominio: usuarios, restaurantes, productos, carrito, pedidos, pagos, repartidores, seguimiento y admin. |
| `pages/` | Páginas generales: Inicio y 404. |
| `layouts/` | Encabezado, navegación, contenido y pie compartidos. |
| `routes/` | Enrutador central y constantes de rutas. |
| `services/` | Cliente HTTP compartido. |
| `config/` | Lectura de variables de entorno. |
| `styles/` | Estilos y colores base. |
| `components/`, `hooks/`, `context/`, `utils/` | Elementos reutilizables entre módulos. |
| `auth/` | Configuración de Entra, sesión MSAL, protección de rutas y retorno seguro. |
| `assets/` | Recursos gráficos. |

Los `.gitkeep` conservan carpetas vacías en Git; pueden retirarse cuando contengan código.

## Agregar un módulo

1. Trabajar en la rama del módulo usando esta base cuando esté integrada en `develop`.
2. Crear pantallas y servicios en `src/features/<dominio>/`.
3. Agregar la URL en `src/routes/routePaths.js` y registrar la pantalla en `AppRouter.jsx`, dentro de la ruta que usa `MainLayout`.
4. Usar `Link` o `NavLink` de `react-router` para navegar. Coordinar el menú en `MainLayout.jsx` con el equipo.
5. Reutilizar el cliente HTTP y ejecutar lint y build antes de entregar.

Si la pantalla requiere sesión, registrar su `<Route>` dentro del grupo `<Route element={<RequireSession />}>`, como `/mi-cuenta`. Mantener las pantallas públicas fuera de ese grupo. No copiar el login ni agregar otra instancia de MSAL en cada módulo.

Ejemplo orientativo en `src/features/restaurantes/restaurantesService.js`; acordar primero el endpoint con el backend:

```js
import httpClient from '../../services/httpClient.js'

export async function listarRestaurantes() {
  const response = await httpClient.get('/restaurantes')
  return response.data
}
```

El transporte HTTP espera como máximo 15 segundos y solicita respuestas JSON; la adquisición previa del token tiene los tiempos propios de MSAL. La pantalla debe gestionar carga, resultados vacíos y errores. El cliente adjunta el token de API por defecto. Para un catálogo realmente público, se puede usar `httpClient.get('/restaurantes', { authRequired: false })` después de acordarlo con el backend.

Coordinar cambios en rutas, layout, estilos globales y dependencias porque son archivos compartidos. Reutilizar `httpClient` en los servicios de dominio, sin escribir otra URL base.

## Validación antes de integrar

```powershell
npm run lint
npm test
npm run build
npm run preview
```

En el navegador, revisar `/`, una dirección inexistente para ver la página 404 y el enlace de vuelta al inicio. Comprobar la presentación en celular y la navegación por teclado. Lint y build no comprueban el comportamiento visual ni la conexión con un backend real.

La suite automatizada cubre configuración, selección de cuenta, login/logout, retorno seguro, rutas privadas y adquisición de tokens. También conecta la configuración y el proveedor de tokens reales del proyecto a Axios contra un servidor HTTP local: comprueba el Bearer ficticio, el bloqueo antes de la red cuando falta consentimiento y los errores 401/403 sin reintentos. Microsoft está simulado en esas pruebas; no acreditan consentimiento real, firma del token ni autorización en el BFF.

Conservar como regresión la prueba manual de `/mi-cuenta?tab=datos#contacto` descrita arriba. La futura integración de backend deberá verificar firma, issuer, audience, scopes y roles con un token real, sin registrarlo ni copiarlo al issue.

El alojamiento final debe devolver `index.html` para rutas del frontend que no correspondan a archivos, permitiendo recargas y enlaces directos con BrowserRouter. Las rutas de API deben seguir llegando al backend.

La base se integró mediante el PR #4 (Issue #2) y la autenticación mediante el PR #18 (Issue #11).

## Perfil / Mi cuenta — Issue #21

Rama `codex/i1-21-perfil-frontend`, iniciada desde `develop` con Carrito backend integrado.
Bloques 1 y 2 publicados en `f3f5826` y `11ec2aa`. Bloques 3 y 4 completados y verificados:
flujo asíncrono de prueba y revisión final. El seguimiento de publicación/PR está en el issue #21.
**Todavía no crea, edita ni consulta usuarios reales**.

- **Tu acceso** muestra nombre e identificador de inicio de sesión entregados por MSAL.
  No se deducen apellido, email de contacto, ID de usuario ni roles a partir de esos datos.
- **Perfil de Pedidos360** empieza como *Perfil aún no consultado*, no como *no tienes
  perfil*: sin consultar el backend no se puede afirmar que exista o falte un registro.
- Solo con `npm run dev` aparece **Ver perfil de ejemplo**. La acción muestra Alex Ejemplo,
  `alex@example.test` y teléfono sin registrar, señalados explícitamente como ficticios.
  **Quitar ejemplo** vuelve al estado inicial. El build de producción no ofrece ese control.
- El ejemplo vive únicamente en el estado del componente; desaparece al recargar, salir
  de la ruta, cerrar sesión o cambiar de identidad. No usa localStorage/sessionStorage,
  no obtiene tokens, no llama a Usuarios ni constituye un fallback por error de API.
- El diagnóstico de permiso API se conserva, separado y bajo un desplegable. Solo esa
  acción explícita puede solicitar un token real en la aplicación; no registra un perfil.

### Contrato de referencia y límites

Referencia local: `backend/services/usuarios-service` (Issue #6).

| Campo de PerfilRequest | Restricción actual del backend |
| --- | --- |
| `nombre` | Obligatorio, recortado, hasta 100 caracteres. |
| `apellido` | Obligatorio, recortado, hasta 100 caracteres. |
| `email` | Obligatorio, formato email, recortado y en minúsculas, hasta 254 caracteres. |
| `telefono` | Opcional, recortado, hasta 30 caracteres; vacío se convierte en null. |

UsuarioResponse añade `id`, `activo`, `creadoEn` y `actualizadoEn`. Identidad, rol, estado,
auditoría e ID no son campos editables. Cambiar el email de contacto no cambia la cuenta
Microsoft. No hay dirección de entrega ni cambio de contraseña en este contrato.

Implementado localmente: formulario de creación/edición y cancelación con validación,
adaptador de prueba para carga, ausencia, errores y confirmación asíncrona y pruebas
ampliadas. Revisión final completada; revisión e integración del PR se siguen en el issue #21.
La conexión mediante el cliente HTTP compartido/BFF y JWT real se hará en la integración,
sin conectar esta pantalla directamente al modo de identidad simulada de Usuarios.

### Comprobar este bloque sin usar una cuenta real

1. Ejecutar `npm run preview:profile` y abrir la URL de loopback que imprime la terminal.
   Es un banco visual de pruebas, **no el arranque normal ni un modo de login de la app**.
2. Verificar la advertencia de sesión ficticia y el estado *Perfil aún no consultado*.
3. Pulsar **Ver perfil de ejemplo**, comprobar los campos, y **Quitar ejemplo**.
   Ambos controles deben funcionar también con Enter/teclado.
4. Mostrar el ejemplo y pulsar **Cambiar cuenta de prueba**: debe volver al estado inicial.
   Repetir con **Cerrar sesión** y **Entrar para continuar**; son dobles locales en este banco.
5. Abrir el diagnóstico y comprobar su error simulado. No contacta Microsoft.
6. Revisar escritorio y ancho móvil (390 px), sin desbordamiento horizontal. Detener con Ctrl+C.

El banco de `tools/` no entra en el build de `index.html` ni modifica `RequireSession`.
No valida autenticación real. En la aplicación normal (`localhost:5173`), la ruta privada
sigue requiriendo la sesión MSAL existente.

Verificado el 10-09-2026: 68 pruebas automatizadas aprobadas, lint y build correctos;
revisión del banco visual en escritorio/móvil, ejemplo/quitar, teclado, cambio de cuenta,
logout/login simulados y diagnóstico. La ruta real sin sesión siguió bloqueada.

### Bloque 2 — Formulario de prueba, sin persistencia

En desarrollo, **Probar creación de perfil** abre un formulario vacío, sin deducir datos
de Microsoft. **Editar ejemplo** precarga el perfil ficticio que ya se ve en pantalla.
Mientras se edita, no se ofrecen los controles para cambiar/quitar el ejemplo.

- Campos permitidos: nombre, apellido, email de contacto y teléfono opcional. El payload
  es una lista explícita de esos cuatro campos: no copia ID, rol, estado o identidad.
- Se recortan extremos, el email pasa a minúsculas y el teléfono vacío pasa a null.
  Los límites de longitud coinciden con PerfilRequest. El email tiene una comprobación
  básica de formato en la UI, no una réplica completa de `@Email`: el servidor deberá
  volver a validar cuando se integre. No se restringe al dominio de Microsoft.
- Etiquetas, obligatoriedad, instrucciones, errores por campo y resumen de errores
  navegable con teclado. Al fallar el envío se enfoca el resumen; no se aplica el borrador.
- **Aplicar al ejemplo** solo cambia la vista en memoria, con confirmación explícita de
  que no se guardó en Usuarios. En edición se deshabilita si no hubo cambios.
- **Cancelar** sin cambios vuelve directamente. Con cambios exige elegir **Seguir editando**
  o **Descartar cambios**; descartar no modifica el perfil anterior. Se devuelve el foco
  al terminar la edición y después de cerrar la confirmación.
- Hay aviso de cambios pendientes y `beforeunload` mientras el borrador está modificado.
  El navegador decide si muestra su advertencia al recargar/cerrar. **No hay bloqueo de
  navegación interna**: salir de Mi cuenta, cambiar identidad o cerrar sesión descarta
  el estado; no se intercepta ni impide el logout de MSAL. No hay guardado automático.

Recorrido en `npm run preview:profile`, siempre con datos ficticios:

1. Pulsar **Probar creación de perfil** y **Aplicar al ejemplo** sin completar: aparecen
   errores para nombre, apellido y email, pero no para el teléfono opcional.
2. Completar nombre `  Andrea  `, apellido `  Prueba  ` y un email sin arroba: sigue
   rechazando. Cambiarlo por `ANDREA@EXAMPLE.TEST` y aplicar: muestra `Andrea`, `Prueba`,
   `andrea@example.test`, teléfono sin registrar y confirmación de cambio solo al ejemplo.
3. Abrir **Editar ejemplo**, cambiar el nombre y cancelar. Probar **Seguir editando** y
   luego **Descartar cambios**; el perfil mostrado debe conservar `Andrea`.
4. Editar el teléfono con `+56 9 0000 0000` y aplicar usando Enter. El cambio aparece
   únicamente en el perfil de ejemplo; el identificador de sesión permanece igual.
5. Con otro borrador pendiente, **Cambiar cuenta de prueba** debe limpiar perfil,
   formulario y mensajes. Abrir la creación de nuevo: todos los campos empiezan vacíos.

Bloque 2 verificado localmente: 82 pruebas aprobadas, lint y build correctos. Comprobados
los pasos anteriores en navegador, foco de errores/cancelación, teclado y formulario a
390 px sin desbordamiento horizontal. El formulario y su payload tienen pruebas nuevas;
no se probó guardado remoto ni se solicitó un token para estas acciones.

El formulario se carga bajo demanda con `React.lazy` y un estado de espera. En el bloque 2,
el archivo separado era de unos 5 kB y el principal de unos 500,53 kB minificados.
Vite avisa cuando supera 500 kB. No es un fallo de compilación ni se elevó el umbral
para ocultarlo; queda como observación para la optimización final del frontend.

### Bloque 3 — Adaptador de prueba y estados asíncronos

Bloque 2 publicado en `11ec2aa`. Este bloque sustituye la aplicación inmediata al ejemplo
por operaciones asíncronas simuladas, con unos 600 ms de espera. No usa HTTP, MSAL ni
almacenamiento persistente para consultar/guardar el perfil. En la app normal la sesión
Microsoft sigue siendo real; solo los datos de perfil son ficticios. El banco visual
además simula la sesión, como se indicó antes.

**Ver perfil de ejemplo** consulta el escenario con perfil; **Probar creación de perfil**
parte de una consulta simulada sin perfil. El selector **Escenario de prueba** y el botón
**Cargar escenario** permiten probar:

| Escenario | Resultado y recorrido |
| --- | --- |
| Perfil existente | Consulta con espera, perfil activo y edición. |
| Sin perfil | Consulta exitosa que devuelve null; permite crear un perfil de prueba. |
| Consulta falla una vez | Muestra error, sin inventar un perfil; Reintentar consulta funciona. |
| Guardado falla una vez | Carga el perfil; primer guardado falla sin alterar el ejemplo. Conserva el borrador; repetir Aplicar al ejemplo funciona. |
| Conflicto al guardar una vez | Primer guardado muestra conflicto y conserva perfil/borrador. Permite revisar y reintentar, o cancelar/descartar. |
| Acceso denegado | Error explícito; no ofrece crear un perfil como si faltara el registro. |
| Perfil inactivo | Consulta de solo lectura; no permite editar ni guardar. |

Los fallos de una sola vez se reinician al pulsar **Cargar escenario**, que crea una nueva
instancia aislada. **Reintentar consulta** reutiliza la instancia actual. Cambiar de
escenario no conserva sus datos previos; es un control de prueba, no una operación real.
Los escenarios no se pueden cambiar durante la edición ni mientras hay una operación.

- Cada pantalla/cuenta tiene controlador y adaptador propios. No hay repositorio global
  de perfiles ni almacenamiento en localStorage/sessionStorage.
- La consulta distingue `idle`, `loading`, `ready`, `empty` y `error`; guardar utiliza
  `saving`. Una excepción nunca se interpreta como ausencia ni carga un perfil de respaldo.
- Mientras se guarda, campos/aplicar/cancelar están bloqueados y el formulario informa
  `aria-busy`. Un bloqueo inmediato en el controlador evita duplicados antes del render.
- Solo se muestra éxito después de recibir y validar la respuesta. Al fallar, se enfoca
  el error, se conserva el borrador y se habilita el reintento **manual**, sin reenvíos automáticos.
- Las respuestas se proyectan a campos conocidos de UsuarioResponse. Datos inválidos
  no reemplazan el perfil visible; errores desconocidos o manipulados no exponen mensajes,
  causas, tokens ni cuerpos originales del adaptador.
- Al salir/cambiar cuenta se cancela mediante AbortSignal y se invalida la operación.
  Incluso si un adaptador ignora la cancelación, su resultado tardío no actualiza la pantalla.
  El adaptador local respeta la señal antes de escribir. Esto **no garantiza rollback de
  peticiones HTTP reales**: ese comportamiento deberá acordarse al integrar.

Interfaz preparada para un adaptador futuro: `read({ signal })` devuelve UsuarioResponse
o null; `save(payload, { signal })` recibe solo PerfilRequest y devuelve UsuarioResponse.
No se han definido aquí las rutas del BFF, el mapeo de errores HTTP ni garantías de
idempotencia/concurrencia del backend. Los errores/conflictos actuales son simulaciones.

Verificado este bloque: **100 pruebas** aprobadas, lint correcto y build completado.
Incluye respuestas tardías, cancelación, aislamiento entre instancias, doble envío,
proyección del payload/respuesta, reintento manual y errores seguros. En navegador se
comprobaron los escenarios, bloqueo de guardado, conservación de borrador, cambio de
cuenta durante un guardado y conflicto/descarte en móvil, sin errores de consola.
En este bloque el build tenía aviso de tamaño: principal de unos 507,77 kB minificados
y formulario separado de unos 4,35 kB. Resuelto en la revisión siguiente.

### Bloque 4 — Revisión final antes de publicar

- `AccountPage` carga el panel simulado bajo demanda **solo en desarrollo**. En producción
  utiliza `ProfilePending`: no importa controlador, adaptador, datos ficticios ni formulario.
  El banco visual también queda fuera del build; no existe un login ficticio en producción.
- Una prueba construye en memoria el bundle de producción y comprueba los módulos incluidos,
  el estado pendiente y la ausencia de controles/datos simulados. El build normal terminó
  sin aviso de tamaño: principal **498,08 kB** minificados (142,57 kB gzip), frente a 507,77 kB.
  No se modificó el umbral de advertencia. El margen sigue siendo pequeño; volver a medir
  al incorporar módulos o la integración real.
- `clearError` solo limpia fallos de guardado: nunca convierte una consulta fallida en
  ausencia ni habilita crear tras acceso denegado. Los códigos de error desconocidos,
  incluso propiedades heredadas como `toString`, se sustituyen por un mensaje conocido.
- El banco visual usa `StrictMode`, igual que la aplicación. Se repitieron creación por
  teclado, validación y enlace al campo, normalización, foco al finalizar, cancelación con
  seguir/descartar y cambio de identidad con borrador. Logout/login simulado reinicia el
  perfil y mantiene la ruta protegida mientras no hay sesión.
- En móvil de 390 px se comprobó fallo de guardado con borrador conservado, aviso enfocado
  y visible y reintento manual. Sin desbordamiento horizontal ni errores/avisos de consola.

Resultado: **104 pruebas** aprobadas, lint y build correctos, `git diff --check` correcto.
Esto acredita el flujo frontend **simulado**, no integración con Usuarios, BFF o JWT real.
Las verificaciones de Microsoft reales anteriores no se repitieron en este bloque.

El cierre del issue requiere revisar e integrar el PR hacia `develop`. La publicación de
commits y apertura del PR no equivalen a integración completada.

## Carrito frontend — Issue #23, bloque 1

Rama `feature/i1-23-carrito-frontend`, desde `develop` con Perfil integrado mediante PR #22
(merge `99dd02e`). Perfil quedó completado en el issue #21. Este primer bloque de Carrito
prepara **solo la lectura**, sin integrar el backend ni modificar datos reales.

- Nueva ruta `/carrito`, enlace en la navegación y protección mediante `RequireSession`.
  Sin sesión se muestra el acceso, no productos ni controles de ejemplo.
- Estado inicial **Carrito aún no consultado**: no afirma que el carrito del usuario esté
  vacío. En producción solo se muestra esta información de integración pendiente.
- En desarrollo hay tres acciones explícitas: **Ver carrito de ejemplo**, **Ver ejemplo
  vacío** y **Quitar ejemplo**. No hacen HTTP, no obtienen tokens ni usan almacenamiento
  persistente. Quitar ejemplo solo regresa al estado no consultado; no vacía un carrito real.
- La vista usa el contrato `CarritoResponse` de `carrito-service`: nombre, precio unitario,
  cantidad y subtotal por línea, más total CLP entero. El ejemplo contiene 2 × $5.500 y
  1 × $1.500, total $12.500. No muestra IDs de identidad/carrito ni inventa nombres de
  restaurantes. Tampoco agrega envío, descuentos, stock reservado o checkout.
- Cada acción crea objetos independientes. Salir de la ruta, cerrar sesión o cambiar
  tenant/homeAccountId/localAccountId desmonta el ejemplo. MSAL sigue siendo la sesión real
  en el arranque normal; no hay un login ficticio allí.
- El panel de desarrollo se carga con importación condicional. Una prueba inspecciona el
  bundle de producción y confirma que excluye el simulador, sus datos y la vista de ejemplo.

### Recorrido reproducible sin credenciales

1. Desde `frontend`, ejecutar `npm run preview:cart` y abrir la URL de loopback impresa.
   Reutiliza el banco de Perfil, bajo StrictMode, iniciando en `/carrito?tab=productos#resumen`.
2. Comprobar el estado no consultado y pulsar **Ver carrito de ejemplo** con Enter.
   Deben verse dos líneas y total $12.500, con aviso de datos ficticios.
3. Pulsar **Ver ejemplo vacío**: muestra ausencia solo para ese escenario y total $0.
   **Quitar ejemplo** vuelve al estado no consultado; no significa eliminar datos remotos.
4. Mostrar productos y **Cambiar cuenta de prueba**: desaparecen. Repetir con **Cerrar
   sesión** y **Entrar para continuar**: vuelve protegido/limpio según corresponda.
5. Navegar entre **Mi cuenta** y **Carrito** y comprobar que no se conserva el ejemplo
   al abandonar la página. Revisar escritorio y móvil de 390 px. Detener con Ctrl+C.

Verificado este bloque: **113 pruebas** aprobadas, lint y build correctos; banco visual
en escritorio y móvil, Enter, foco tras quitar el ejemplo, vacío, cambio de cuenta y
logout/login simulados. Consola sin errores/avisos. El principal de producción mide
499,19 kB minificados: sin aviso, pero con poco margen; medir en los próximos bloques.

Pendiente en esta rama: agregar productos ficticios, cantidades (1–99), eliminar/vaciar,
un restaurante por carrito y hasta 50 productos diferentes según el contrato local;
después adaptador asíncrono, errores/reintentos y revisión final. La integración HTTP,
catálogo real, Pedidos y JWT se harán posteriormente. Docker va en otra rama.
