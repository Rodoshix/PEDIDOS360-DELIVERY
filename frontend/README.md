# Pedidos360 Delivery — Frontend

Base compartida con React, Vite, JavaScript, React Router y Axios. Incluye Inicio, página 404, layout y cliente HTTP. Se está incorporando autenticación con Microsoft Entra ID en el Issue #11.

Estado de este bloque: configuración MSAL, login/logout, cuenta activa y ruta privada `/mi-cuenta` con retorno al destino solicitado. Todavía no hay envío de tokens por Axios. El responsable confirmó la prueba manual de login, persistencia al recargar y logout del bloque anterior; falta confirmar el retorno real a una ruta privada. Las 40 pruebas automatizadas cubren configuración, sesión, destinos y renderizado de rutas, sin credenciales reales.

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
VITE_ENTRA_TENANT_ID=<id-del-directorio-Tenant-CloudNative>
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

En el portal se configuraron frontend SPA, API con `access_as_user`, consentimiento concedido y rol CLIENTE asignado a la cuenta de prueba. Los roles CLIENTE/ADMIN pertenecen a la API; no se debe asumir que aparecen en el ID token del frontend. Login y logout reales fueron confirmados por el responsable; la validación de tokens en BFF/servicios sigue pendiente.

Referencia: [inicialización de MSAL React](https://learn.microsoft.com/en-us/entra/msal/javascript/react/getting-started).

### Iniciar y cerrar sesión

1. Abre `http://localhost:5173` y pulsa **Iniciar sesión con Microsoft**. Se abre Microsoft en la misma pestaña; las credenciales y MFA se introducen allí, nunca en formularios propios ni en el repositorio.
2. Selecciona la cuenta del directorio configurado y completa el acceso. Al regresar, el encabezado debe mostrar su nombre y cuenta sin recargar manualmente.
3. Recarga: se conserva la cuenta de la caché de esa pestaña. Si hay varias cuentas y ninguna activa, se pide una selección mediante el botón de login, sin elegir arbitrariamente la primera.
4. Pulsa **Cerrar sesión** y completa la salida de Microsoft. Debe regresar al inicio con el botón de login. No basta con cerrar la pestaña para garantizar la salida de Microsoft.
5. Si cancelas o falla el acceso, se muestra un mensaje controlado y puedes intentarlo otra vez. No se reintenta automáticamente y los botones se bloquean durante una operación.

El retorno se procesa con `handleRedirectPromise` antes de montar las rutas. Este bloque usa únicamente `loginRedirect`/`logoutRedirect`, con la URI raíz ya registrada. No usa popup ni `ssoSilent`; esas modalidades de MSAL Browser 5 requieren una página de redirect bridge dedicada y su registro en Entra. La renovación silenciosa de tokens y sus alternativas se revisarán en el bloque de acceso a la API.

Login solicita `openid` y `profile`. El permiso `access_as_user` está configurado para el siguiente bloque de adquisición de tokens; aún no hay peticiones autenticadas al backend. Mostrar una cuenta no acredita autorización ni rol ADMIN. Las verificaciones del directorio en la UI tampoco sustituyen la validación criptográfica de tokens en el backend.

### Rutas privadas y regreso después del login

- Inicio y 404 siguen siendo públicos. `/mi-cuenta` es una vista mínima de sesión, no el módulo funcional de Perfil.
- `RequireSession` no monta el contenido privado mientras MSAL está ocupado ni cuando falta una cuenta. Muestra una espera o una invitación a entrar; nunca inicia redirecciones automáticamente.
- Tanto el botón del encabezado como **Entrar para continuar** conservan ruta, consulta y fragmento. El destino se guarda en `sessionStorage`; solo una clave aleatoria se envía en `state`, siguiendo la [recomendación de Microsoft para estado personalizado](https://learn.microsoft.com/en-us/entra/msal/javascript/browser/mip-pass-custom-state).
- Tras una respuesta válida del directorio se consume la clave una sola vez y se reemplaza la URL antes de montar las rutas. No se hace una segunda petición de página. La URI SPA de Entra sigue siendo `http://localhost:5173`; no hay que registrar cada ruta privada.
- Se rechazan URLs externas, direcciones ambiguas, caracteres de control, respuestas OAuth y destinos malformados. Sin destino válido, con clave distinta o transcurridos 15 minutos se vuelve a `/`. Cancelación, fallo, logout y arranque sin respuesta descartan el destino pendiente; no quedan reintentos automáticos.
- Esta protección es de navegación del frontend. El backend debe validar tokens y permisos por separado.

Prueba manual pendiente de este bloque:

1. Sin sesión, abrir `http://localhost:5173/mi-cuenta?tab=datos#contacto`: debe pedir iniciar sesión sin mostrar el contenido privado.
2. Pulsar **Entrar para continuar** y completar Microsoft. Debe regresar a esa misma dirección, conservando `?tab=datos#contacto`, y mostrar **Mi cuenta**. Esos parámetros sirven para verificar el retorno; no activan un formulario.
3. Recargar la página: debe seguir mostrando la vista privada con la sesión activa.
4. Cerrar sesión y volver a abrir `/mi-cuenta`: debe pedir entrar otra vez. Inicio y una ruta inexistente deben seguir funcionando sin sesión.

## Comandos disponibles

Ejecutar desde `frontend/`:

| Comando | Uso |
| --- | --- |
| `npm ci` | Instalar las versiones de `package-lock.json`. |
| `npm run dev` | Iniciar el servidor de desarrollo. |
| `npm run lint` | Revisar el código con Oxlint. |
| `npm test` | Probar configuración, sesión, destinos seguros y rutas con Node.js y renderizado React, sin Azure ni credenciales. |
| `npm run build` | Generar la aplicación en `dist/`. |
| `npm run preview` | Revisar localmente el resultado de build. |

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

El cliente espera como máximo 15 segundos y solicita respuestas JSON. La pantalla debe gestionar carga, resultados vacíos y errores. Aún no adjunta tokens ni incluye interceptores de autenticación.

Coordinar cambios en rutas, layout, estilos globales y dependencias porque son archivos compartidos. Reutilizar `httpClient` en los servicios de dominio, sin escribir otra URL base.

## Validación antes de integrar

```powershell
npm run lint
npm test
npm run build
npm run preview
```

En el navegador, revisar `/`, una dirección inexistente para ver la página 404 y el enlace de vuelta al inicio. Comprobar la presentación en celular y la navegación por teclado. Lint y build no comprueban el comportamiento visual ni la conexión con un backend real.

El alojamiento final debe devolver `index.html` para rutas del frontend que no correspondan a archivos, permitiendo recargas y enlaces directos con BrowserRouter. Las rutas de API deben seguir llegando al backend.

La base se integró mediante el PR #4 (Issue #2). La autenticación se trabaja en `feature/i1-11-auth-msal`, con PR final hacia `develop`, asociado al Issue #11.
