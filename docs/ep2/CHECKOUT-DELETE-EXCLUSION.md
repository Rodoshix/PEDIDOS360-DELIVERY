# #70 — Exclusión del DELETE en checkout

Base: develop `8c573a8c0b21d0ed45d08b30c3373be273e07367`. Refs #70.
Preparación inactiva por defecto; no corte ni autorización de activación.

## Fuente de selección y frontera de confianza

Constante de módulo `checkoutCoordination`, incorporada al artefacto por Vite desde
VITE_PEDIDOS_CARRITO_MODE. Valor ausente → HTTP; únicamente HTTP/RABBITMQ válidos.
Vite y Docker validan la configuración. El build Docker incluye ARG HTTP por defecto.
No hay selector por URL, local/sessionStorage, props del panel, atributo DOM, payload
remoto ni header nuevo. No cambia ningún contrato HTTP.

Esta fuente es confiable como configuración del artefacto administrado, no como
prueba del modo que utilizó el servidor para un pedido. El frontend no puede detectar
una discrepancia entre builds/configuraciones backend. Un cliente modificado puede
seguir llamando DELETE. No se afirma exclusión global ni protección de clientes antiguos.
Si el despliegue requiere coexistencia dinámica de modos por pedido, hará falta una
señal autenticada del servidor vinculada a esa creación, con contrato aprobado antes
de implementarla; esta entrega solo prepara selección estática coordinada.

## Comportamiento

| Caso | Resultado |
|---|---|
| HTTP + 201 | DELETE posterior, anuncio de vaciado solo después del éxito |
| HTTP + DELETE fallido | Reintento exclusivo del DELETE; conserva pedido |
| RABBITMQ + 201 | Sin DELETE; registro e intención no se presentan como vaciado |
| RABBITMQ + consulta | Solo GET /carrito; refleja última lectura y productos posteriores |
| RABBITMQ + consulta fallida | Permite repetir GET, sin POST/DELETE |
| Doble submit/callback antiguo tras éxito | No emite segundo POST en la misma pantalla |

El guard de pedido registrado complementa la exclusión síncrona existente. No se
agrega idempotencia al endpoint de Pedido ni se promete protección entre recargas.
Los resultados inciertos conservan su comportamiento anterior. No se cambia MSAL,
rutas, adaptadores de escrituras, consumer/outbox Carrito ni Pago→Pedido.

## Corte pendiente y clientes antiguos

Para el entorno académico controlado: suspender checkout, inventariar sesiones/clientes,
cerrar las pestañas anteriores, servir el artefacto correcto y comprobar la reapertura
antes de coordinar modos backend. La comprobación debe incluir solicitudes observadas;
publicar assets o invalidar caché por sí solos no retira pestañas ya abiertas.

Si existen clientes no controlados y no se puede acreditar su retirada, el corte
permanece bloqueado hasta aprobar una barrera servidor. Bloquear DELETE globalmente
rompería también vaciado manual; no se implementó ni se seleccionó esa política.
Rollback requiere resolver comandos pendientes antes de restaurar DELETE automático,
según el procedimiento existente de #82. Un simple cambio de build/flag no es seguro.

## Evidencia local del 9 de octubre de 2026

- Suite frontend completa: 229 pruebas exitosas, cero fallos/omisiones; seis casos nuevos.
- Lint de src/tests/tools/vite.config.js: aprobado.
- Build producción HTTP y RABBITMQ con write:false: aprobados; INVALID rechazado.
- checkoutCoordination.test.js carga JSX y handlers reales con Vite. Hooks, router,
  sesión y cliente HTTP son fixtures; no es un navegador real ni Entra live.
- Incluye HTTP predeterminado, DELETE fallido/reintento, doble submit y callback
  obsoleto, ausencia de DELETE/reintento en RabbitMQ, GET con productos posteriores,
  lectura vacía y error de consulta. No prueba modificación concurrente del backend:
  sus garantías permanecen en las pruebas ya integradas de #82.
- Las primeras pruebas del harness fallaron por falta de fixture del router y por
  espacios en la representación textual; se corrigió el montaje, sin relajar los
  resultados funcionales esperados. Esos intentos no se contabilizan como exitosos.
- Las suites existentes cubren rutas, MSAL/adaptadores, contratos y build productivo.
  No se reejecutaron suites backend, Docker de producción, RabbitMQ/PostgreSQL ni AWS.

Hashes de fuentes LF y del log externo de la ejecución completa en
CHECKOUT-DELETE-tests.json. No contiene claves ni secretos.

READY FOR INDEPENDENT AUDIT: preparación de frontend, no activación del corte.
