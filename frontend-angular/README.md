# PEDIDOS360 Angular — candidato local EP2

Aplicación Angular 21 standalone separada de `frontend/` (React, intacto).
No cambia el frontend desplegado ni selecciona imágenes AWS. Las rutas `/perfil`,
`/restaurantes` y `/compras` ejecutan los endpoints existentes del BFF/API Manager:
perfil, catálogo, agregado al carrito, checkout, pedidos propios y consulta/registro
de pagos. Usa `pedidoId` y `pagoId` de los DTO reales; no envía usuario ni tenant.
No incluye pantallas administrativas nuevas.

## Configuración y ejecución

Node 22.19 o compatible con Angular 21; `npm ci` usa el lock versionado.
Si una instalación inicial encuentra el defecto de peers de npm 10, se utilizó
`npx npm@11.6.4 install` para generar el lock. No se usó `--force` ni
`--legacy-peer-deps`. Docker `npm ci` se comprobó también en Linux.

```powershell
cd frontend-angular
npm ci
# Valores PÚBLICOS de la aplicación Entra existente. Nunca client secrets.
$env:P360_API_BASE_URL='https://<api-manager>/api'
$env:P360_ENTRA_CLIENT_ID='<frontend-client-uuid>'
$env:P360_ENTRA_TENANT_ID='<tenant-uuid>'
$env:P360_ENTRA_REDIRECT_URI='http://localhost:4200'
$env:P360_ENTRA_API_SCOPE='api://<api-client-uuid>/access_as_user'
npm run configure
npm test
npm run lint
npm run build
npm start
```

`public/config.json` se genera y está ignorado. El redirect debe estar registrado
como SPA en Entra y coincidir con el origen servido. La configuración inválida
falla cerrada. HTTP es predeterminado; `P360_CHECKOUT_MODE=RABBITMQ` es una opción
de empaquetado futuro y no activa mensajería en backend. No se elige mediante
query string, headers o localStorage. El overlay local
`infrastructure/aws/compose.angular-build.yml` mantiene HTTP explícito y conserva
la seguridad del servicio Compose base. Ningún artefacto AWS fue activado.

MSAL Browser 5.25 realiza Authorization Code/PKCE mediante `loginRedirect` y
`handleRedirectPromise` en la misma página. No se implementa un flujo OAuth casero,
popup, implicit grant ni silent iframe. Tokens silenciosos usan únicamente cache/
refresh token; cuando requieren interacción, el usuario vuelve a iniciar sesión.
Solo el access token delegado va al destino API configurado; no se usa el ID token
como bearer. La cuenta queda vinculada durante cada consulta y el backend conserva
la validación independiente de JWT, roles y tenant. Los logs MSAL no imprimen PII.
[Comportamiento de redirect de MSAL](https://github.com/AzureAD/microsoft-authentication-library-for-js/blob/dev/lib/msal-browser/docs/redirect-bridge.md).

## Seguridad de las escrituras

El retry de lectura solo repite GET. HTTP checkout hace POST una vez y después
DELETE; un DELETE fallido solo permite reintentar ese DELETE. RabbitMQ no emite
DELETE ni asegura vaciado tras 201. Dirección y estado se conservan al recuperar
lecturas. Un resultado incierto bloquea otra escritura y solicita consultar el
estado. Las claves de idempotencia de Pago son UUID estables por intento y no se
renuevan por un GET. El registro de intentos está separado por cuenta en
sessionStorage y no contiene tokens, dirección ni payload de negocio.

Límite deliberado del frontend mínimo: un intento de escritura no se restablece
automáticamente durante esa sesión de pestaña, incluso si una lectura no encuentra
el recurso. No garantiza dedupe entre pestañas/navegadores ni agrega idempotencia
server-side a POST Pedido. Es conservador ante incertidumbre, no una garantía
exactly-once. React permanece disponible para los flujos completos ya auditados.

## Evidencia y límites

Las pruebas Angular usan TestBed, plantillas reales, HttpTestingController y
fixtures de la interfaz SDK. No son login Entra live, API Manager cloud ni E2E
Angular contra ocho aplicaciones reales. La inspección de navegador verificó la
pantalla real de inicio y su bloqueo sin sesión. Se requiere una demostración
autenticada con la configuración Entra existente antes del corte cloud.

Vitest y su proveedor están fijados en 4.1.11, después de detectar avisos en la
selección inicial 4.1.0. No se utiliza browser-mode expuesto ni un servidor de test
público. [Aviso corregido](https://github.com/advisories/GHSA-82fw-gwwq-j7x9).
Resultados completos: `docs/ep2/LOCAL-ACADEMIC-EP2.md` y su manifiesto JSON.
