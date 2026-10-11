# Frontend #60 — fase 6 CLIENTE: Pedidos y Pagos

Base: `bbc2da6eb19d276d62e1a1c3d1dc3722db44f09b` (merge #103). Rama: `codex/60-orders-payments`. El HEAD definitivo se obtiene del PR; el manifiesto fija el contenido mediante hashes, sin referencias circulares al commit que lo contiene.

## Alcance

| Ruta existente | Presentación | Datos/acciones conservados |
|---|---|---|
| `/confirmar-pedido` | RealConfirmarPedidoPanel | Dirección, líneas del carrito, total, creación, vaciado separado e incertidumbre |
| `/mis-pedidos` | RealMisPedidosPanel / ClientPedidoSummary | GET `/pedidos/me`, ID, fechaCreacion, estado, dirección, total y detalle |
| `/pedidos/:id` | RealPedidoDetallePanel / ClientPedidoDetail | GET `/pedidos/{id}`, productoId, cantidades, precioUnitario, subtotal y total; sin transiciones ADMIN |
| `/pago/:pedidoId` | RealPagoPanel | Consulta existente, TARJETA/EFECTIVO, monto, estado, reintento explícito con clave original y nuevo intento tras rechazo definitivo |

`ClientPedidoView.jsx` separa la presentación CLIENTE de los componentes administrativos, que permanecen intactos. No incorpora consultas ni acciones de negocio. El contrato de líneas no proporciona nombres: se muestra Producto #ID. Fechas e importes utilizan los formateadores existentes.

Se reutilizan tokens y feedback compartido. Botones nativos conservan sus callbacks; estilos y enlaces de navegación tienen altura mínima de 44 px. Dirección y método mantienen labels, validaciones relacionadas, foco visible y disabled. Los avisos críticos reciben foco programático con tabIndex=-1 y role=alert. CSS de alcance CLIENTE, sin rediseño de componentes compartidos.

## Garantías conservadas

- Handlers de creación, recuperación, DELETE e idempotencia sin cambios; controllers, adaptadores, MSAL, DTO y endpoints sin cambios.
- POST concurrente bloqueado por las guardas existentes. Incertidumbre del pedido retira el formulario; no se añade reintento automático.
- Recuperación de lecturas únicamente GET, conservando dirección y clave. Pago incierto permite el reintento explícito existente con la misma Idempotency-Key.
- HTTP predeterminado: creación seguida de DELETE; ante fallo de vaciado, reintento exclusivamente DELETE.
- RABBITMQ: sin DELETE automático ni botón de reintento DELETE. El texto mantiene que 201 registra el pedido y no confirma vaciado; consultar carrito no crea otro pedido.
- Vistas propias y navegación conservadas. No se modifican acceso ADMIN, flujos de coordinación, backend, flags operativos ni infraestructura.

## Pruebas ejecutadas

| Validación | Resultado |
|---|---|
| Focalizadas: clientCommerce, checkoutCoordination, readRecovery, pagoEmpty, confirmarPedidoFlujo, vaciadoCarritoFlujo | 35 correctas, 0 fallos, 0 omisiones |
| `npm test` completo | 253 correctas, 0 fallos, 0 omisiones (incluye las focalizadas) |
| Nuevas regresiones clientCommerce | 13, incluidas en 253; pruebas anteriores sin modificar |
| `npm run lint` | oxlint, exit 0 |
| Producción HTTP y RABBITMQ | ambos exit 0 |
| Revisión de navegador con fixtures | escritorio 1280 px y móvil 320/390 px; estados y teclado inspeccionados |

Las regresiones nuevas utilizan panels/controllers/adaptadores reales con hooks, sesión y transporte controlados. Cubren POST único, incertidumbre, clave estable, EFECTIVO, estados aprobado/pendiente/rechazado, lectura propia, navegación, recuperación GET y ausencia de acciones ADMIN. Las regresiones anteriores cubren recuperación checkout/pago, dirección y coordinación. Son pruebas locales: no validan transacciones reales, Entra live ni backend desplegado.

Comandos, desde `frontend/`:

```powershell
node --test tests/clientCommerce.test.js tests/checkoutCoordination.test.js tests/readRecovery.test.js tests/pagoEmpty.test.js tests/confirmarPedidoFlujo.test.js tests/vaciadoCarritoFlujo.test.js
npm test
npm run lint
$env:VITE_PEDIDOS_CARRITO_MODE='HTTP'
npm run build -- --outDir <directorio-desechable>/build-http
$env:VITE_PEDIDOS_CARRITO_MODE='RABBITMQ'
npm run build -- --outDir <directorio-desechable>/build-rabbitmq
Remove-Item Env:VITE_PEDIDOS_CARRITO_MODE
```

Ambos builds mantienen la advertencia de chunk superior a 500 kB (~623 kB, ~177 kB gzip). No se cambió el límite ni se ocultó la advertencia. No se añadieron dependencias.

## Banco visual y capturas

`npm run preview:ui` inicia el banco loopback existente. Se amplía exclusivamente `tools/fixtures/previewApi.mjs`: pedidos/pagos ficticios en memoria y escenarios `commerce-empty`, `commerce-approved`, `commerce-pending`, `commerce-rejected`, `commerce-uncertain-order`, `commerce-uncertain-payment`, `commerce-delete-error`. Selección: POST loopback `/__preview/scenario` con el nombre como texto. Los botones normal/error/slow de la herramienta reinician la vista en Mi cuenta; para conservar la pantalla durante una recuperación se cambia el escenario mediante ese endpoint local.

No se generan pedidos/pagos reales. Las escrituras ficticias requieren clicks explícitos del banco; no entran en el bundle productivo ni son fallback del producto. Los errores 503 son respuestas inyectadas; los timeouts de regresión son excepciones controladas, no particiones reales de red.

Capturas JPEG en `docs/evidence/60-client-commerce/`: historial con/vacío/carga/error; detalle escritorio/320/390; pago sin registro/aprobado/pendiente/rechazado/incierto; checkout escritorio/320, creado con fallo DELETE, recuperación DELETE y resultado incierto escritorio/móvil. Cada imagen se inspeccionó visualmente después de esperar el texto esperado y carga finalizada, salvo la captura de carga que exige aria-busy=true. `browser-review.json` conserva DOM, dimensiones, foco y consola asociados. No se observaron errores/warnings de JavaScript ni desbordamiento horizontal en las capturas. Selección EFECTIVO mediante Space, Tab y Enter; dirección seguida de Tab muestra foco visible de 2 px.

El manifiesto `frontend-60-client-commerce-tests.json` registra SHA-256 de capturas, logs y fuentes (texto UTF-8 normalizado LF). La revisión de navegador usa autenticación ficticia; el menú global puede mostrar acceso administrativo de la cuenta de prueba, sin acciones ADMIN dentro de Pedidos/Pagos. No se afirma E2E con servicios reales, sesión Entra ni navegador con broker RabbitMQ: ese modo se comprueba mediante regresiones y build.

## Límites y estado

Pendientes fuera de este PR: validación desplegada/Entra live, corte operativo y rollback de #70/#71/#72. No se cambian permisos, AWS ni flags. No se modifica administración ni se cierra #60. PR Draft para revisión independiente; sin merge.
