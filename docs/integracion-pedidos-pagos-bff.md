# Integración Pedidos/Pagos: I1/I5 e I3

Seguimiento: #48 (BFF/frontend) y #47 (coordinación y servicios I3).

## Contrato de confianza

El BFF propaga únicamente el Bearer validado a orígenes configurados. Pedidos/Pagos
revalidan JWT (firma, issuer, audiencia API, expiración, scope y roles). No confiar
en X-User-Id, X-Roles ni cookies del navegador. Cada servicio obtiene el id numérico
del actor consultando Usuarios GET /usuarios/me con el mismo Bearer. Usuarios
resuelve tid + oid; un perfil inexistente/inactivo no habilita una operación ni se
crea automáticamente. La pertenencia del pedido/pago se comprueba en I3.

CLIENTE y ADMIN son los roles definidos actualmente. No asumir que REPARTIDOR se
emite ni que un rol por sí solo identifica al repartidor asignado a una entrega.

## Primer bloque: consultas

| Ruta BFF | Destino | Permiso adicional a access_as_user |
| --- | --- | --- |
| GET /pedidos | Pedidos /pedidos | ADMIN |
| GET /pedidos/me | Pedidos /pedidos/me (nuevo en I3) | CLIENTE o ADMIN, historial propio |
| GET /pedidos/{id} | Pedidos, misma ruta | CLIENTE propietario o ADMIN |
| GET /pagos/{id} | Pagos, misma ruta | CLIENTE propietario o ADMIN |
| GET /pagos/pedido/{id} | Pagos, misma ruta; devuelve lista | CLIENTE propietario o ADMIN |

La ruta administrativa por usuario queda pendiente. Las respuestas no se almacenan en
caché; los errores internos se sustituyen por mensajes neutros, sin seguir redirects
ni reintentar escrituras. Los filtros/query strings no se propagan automáticamente.

## Configuración y habilitación

- BFF_PEDIDOS_PAGOS_ENABLED=false por defecto: incluso un ADMIN queda bloqueado.
- PEDIDOS_SERVICE_URL=http://127.0.0.1:8085
- PAGOS_SERVICE_URL=http://127.0.0.1:8086
- Orígenes sin ruta, credenciales ni query; HTTPS salvo HTTP loopback local.

No habilitar en un entorno real hasta que I1/I5 implemente y verifique JWT, resolución
del usuario, pertenencia e historial propio. Las pruebas del BFF usan un servidor
HTTP controlado: no certifican la seguridad de los servicios reales.

## Siguientes bloques y responsabilidades

### Comandos BFF implementados (también sujetos al interruptor de habilitación)

- POST /pedidos: CLIENTE o ADMIN; restauranteId, direccionEntrega (1-255 caracteres
  no vacíos) e items de productoId/cantidad enteros positivos. Sin precios ni usuarioId.
- POST /pagos: CLIENTE o ADMIN; pedidoId y metodo TARJETA/EFECTIVO. Exige una única
  cabecera Idempotency-Key de 1-80 caracteres alfanuméricos, guion o guion bajo.
  El BFF la transmite sin reemplazarla ni generar otra. El servicio garantiza la
  idempotencia; el BFF no almacena resultados ni reintenta automáticamente.
- PUT /pedidos/{id}/estado: ADMIN; solo el campo estado. I3 valida transición y reglas.
- PUT /pagos/{id}/aprobar: ADMIN; sin cuerpo.

Se exige además access_as_user. CORS acepta Idempotency-Key únicamente en /pagos,
desde los orígenes configurados. Los adaptadores y pantallas de I3 están integrados
desde el PR #51; falta validar el recorrido con los servicios e identidad reales.
POST /pedidos no tiene todavía contrato de idempotencia: un timeout no autoriza
a reenviarlo automáticamente, pues podría duplicar el pedido.

I3 aportó servicios, reglas y adaptadores frontend. I1/I5 continúa la integración
de BFF/CORS, seguridad Entra, identidad, Docker y AWS sobre los cambios mergeados.
POST /pagos requerirá Idempotency-Key: misma clave en reintento incierto, nueva
para otra operación. Precios CLP se calculan en backend. El carrito no se vacía
hasta confirmar creación del pedido. Aprobar pagos y estados operativos será
inicialmente ADMIN. Pagos con tarjeta siguen siendo simulados, sin cargos reales.

La reconciliación autónoma Pagos -> Pedidos usa autenticación de servicio limitada.
No persistir el token del usuario para el scheduler ni recurrir a identidad-local.
La prueba del worker descrita abajo no certifica todavía el recorrido de compra.

## Aclaración publicada en #47

Audiencia v2 de ambos servicios: `13c0f63f-2007-41c4-8d9f-02640b8a1886`.
Tenant: `a048ca4e-cd7f-4a01-a43e-cb4deccf1ff2`. El azp delegado permitido es
`5388c832-53e4-45c8-a3be-6875b69e1a51`. Son identificadores públicos configurables.

Contrato de servicio implementado tras integrar el PR #49:

- Registro separado `pedidos360-pagos-worker`, rol de aplicación `Pedidos.Confirmar`
  (Applications) en la API, permiso y consentimiento administrativo para ese worker.
- Client credentials con scope `api://13c0f63f-2007-41c4-8d9f-02640b8a1886/.default`.
- `PUT /internal/pedidos/{id}/confirmacion-pago`, sin cuerpo. JWT v2 con la misma
  audiencia/issuer, azp exacto del worker, rol indicado, ausencia de scp y firma/
  vigencia válidas. Client ID del worker: `5edfad3c-8147-4a6f-bb7d-fabd3c4ad8f6`.
- Solo CREADO -> CONFIRMADO; 204 también si ya confirmado o avanzado, 409 si
  cancelado, 404 si inexistente. Operación transaccional e idempotente.
- Sin exposición por BFF/CORS; tokens delegados no acceden a la ruta interna y
  tokens de aplicación no acceden a rutas delegadas. No consultar Usuarios/me
  con identidad de aplicación. Pagos conserva su validación delegada de pertenencia
  en la creación y solo confirma pagos persistidos elegibles para reconciliación.
- I1/I5 coordina alta Entra y contrato/revisión; I3 adapta cliente/endpoint. Credencial
  solo backend, fuera de repositorio, VITE, comentarios y logs. Tokens app-only
  en memoria, nunca token de usuario persistido. HTTPS salvo loopback local.

Crear pedido -> 201 válido con pedidoId -> vaciar carrito. Si falla el vaciado,
reintentar solo el vaciado y conservar referencia al pedido creado. No recrearlo.

Referencia: [Client credentials de Entra](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-client-creds-grant-flow).

## Proveedor worker y validación real (2026-09-13)

Pagos incorpora `EntraWorkerTokenProvider`: client credentials contra Entra,
sin redirecciones, timeout, token en memoria y renovación 60 segundos antes
del vencimiento. Los errores no incluyen credenciales ni respuestas del servidor.
Se activa con `PAGOS_WORKER_ENABLED=true` y `PEDIDOS_INTERNO_ENABLED=true`.
Requiere `ENTRA_TENANT_ID`, `ENTRA_API_CLIENT_ID`, `PAGOS_WORKER_CLIENT_ID`
y `PAGOS_WORKER_CLIENT_SECRET` inyectados en el proceso de Pagos. El archivo
local ignorado `.env.worker.local` no se carga automáticamente. Nunca pasar
el secreto por argumentos, frontend ni archivos versionados.

Pruebas opt-in: `RUN_ENTRA_WORKER_LIVE=true`. `EntraWorkerLiveTests` en Pagos
comprueba adquisición real y caché. `WorkerEntraLiveTests` en Pedidos requiere
además `WORKER_ACCESS_TOKEN` en el entorno, usa PostgreSQL efímero y valida
firma con las claves reales de Entra: dos confirmaciones HTTP 204, estado
CONFIRMADO y rechazo del mismo token en `/pedidos/me`. Ambas pasaron.
No registran tokens y se omiten en la ejecución normal sin la bandera.

Pendiente: identidad delegada real en Pedidos/Pagos, consulta del catálogo para
precios y pertenencia, y recorrido completo frontend -> BFF -> servicios.
Mantener el interruptor BFF deshabilitado hasta verificar ese recorrido.
