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

No habilitar en un entorno real hasta que I3 implemente y verifique JWT, resolución
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
desde los orígenes configurados. Los adaptadores y pantallas reales siguen pendientes.
POST /pedidos no tiene todavía contrato de idempotencia: un timeout no autoriza
a reenviarlo automáticamente, pues podría duplicar el pedido.

I3 adapta sus servicios, reglas, Docker y pruebas. I1/I5 implementa BFF/CORS,
adaptadores frontend y prueba integrada sin editar esos servicios en paralelo.
POST /pagos requerirá Idempotency-Key: misma clave en reintento incierto, nueva
para otra operación. Precios CLP se calculan en backend. El carrito no se vacía
hasta confirmar creación del pedido. Aprobar pagos y estados operativos será
inicialmente ADMIN. Pagos con tarjeta siguen siendo simulados, sin cargos reales.

La reconciliación autónoma Pagos -> Pedidos necesita autenticación de servicio
limitada, pendiente de concretar en #47. No persistir el token del usuario para
el scheduler ni recurrir a identidad-local. Este punto bloquea el cierre del flujo
real, no las pruebas aisladas del BFF.
