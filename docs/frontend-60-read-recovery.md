# Recuperación de lecturas — issue #60

Base: `3a15c24eb80443b06b942b17d11833cb30b97f8b`. Rama: `codex/60-read-recovery`.
Validación local: 2026-10-09 (America/Santiago).

## Cambio mínimo

- Pago ofrece **Reintentar consulta** solamente ante `LOAD_FAILED` de lectura.
  Invoca `controller.load(pedidoId)`, sin generar claves ni llamar a registrar.
- Checkout ofrece **Reintentar consulta del carrito** ante fallo transitorio de la
  lectura inicial. Ejecuta GET /carrito, conserva dirección y controller, impide
  consultas solapadas y cancela la recuperación al desmontar/revalidar sesión.
- Los dos contenedores de error conservan `role="alert"` y usan `tabIndex={-1}`:
  reciben foco programático sin incorporarse a la secuencia de tabulación.

Controllers, adaptadores, MSAL, DTO, endpoints, coordinación y estilos no cambian.
HTTP continúa predeterminado. RabbitMQ no ejecuta DELETE automático; un 201 no
confirma el vaciado. Los resultados inciertos y el reintento de escritura con la
misma Idempotency-Key conservan su tratamiento previo.

## Regresiones y resultados

| Ejecución | Resultado | Naturaleza |
|---|---|---|
| Cinco archivos focalizados | 22 aprobadas, 0 fallos/omisiones | Controllers, JSX/handlers y transporte/auth fixtures |
| Frontend completo | 234 aprobadas, 0 fallos/omisiones | Suite Node/Vite, incluye 5 regresiones nuevas |
| npm run lint | Correcto | oxlint, no ESLint |
| Build HTTP | Correcto | VITE_PEDIDOS_CARRITO_MODE=HTTP |
| Build RabbitMQ | Correcto | VITE_PEDIDOS_CARRITO_MODE=RABBITMQ |
| Teclado checkout en Opera | Correcto | Banco existente preview:ui, backend y sesión ficticios |

`readRecovery.test.js` cubre GET 503/timeout en ambos paneles, recuperación sin
POST/DELETE, llamadas solapadas, tabIndex y efecto de foco. La dirección se siembra
en el fixture como borrador previo a la lectura y debe sobrevivir a la recuperación.
El caso de clave invoca manualmente la lectura del controller tras un POST incierto:
verifica que recuperar no escribe y que el reintento explícito conserva la misma
clave. No representa una nueva acción de lectura disponible tras todo error de POST.

Los casos existentes verifican POST único, incertidumbre, HTTP POST→DELETE,
reintento exclusivo de DELETE y RabbitMQ sin DELETE ni segundo pedido.

En navegador se provocó 503 con el escenario del banco local, manteniendo la ruta
checkout y cambiando de cuenta ficticia. El alert recibió foco automáticamente;
Tab alcanzó el botón; al restaurar el escenario normal, Enter recuperó el formulario.
No se pulsó Confirmar pedido. Foco de Pago se verificó mediante fixture de hooks;
su recorrido de teclado en navegador no se ejecutó. No se ejecutó Entra live,
compra E2E contra servicios reales, PostgreSQL/RabbitMQ ni AWS.

Un primer intento del fixture de foco falló porque ejecutaba efectos antes de
conectar refs. Se corrigió el orden del harness para representar el commit de React;
no se debilitó la aserción. Los resultados de la tabla corresponden al harness final.
El navegador integrado quedó en blanco y Chrome no estaba disponible; se utilizó
Opera. El banco se reinició después de las pruebas Vite. El nuevo harness usa caché
separada y no descubre dependencias para evitar interferencias con preview:ui.

Ambos builds mantienen la advertencia de chunk principal superior a 500 kB.
No se acomete optimización ni rediseño en este PR.

## Reproducción

Desde frontend, con dependencias instaladas:

```powershell
node --test tests/readRecovery.test.js tests/checkoutCoordination.test.js tests/confirmarPedidoFlujo.test.js tests/vaciadoCarritoFlujo.test.js tests/pagoEmpty.test.js
npm test
npm run lint
$env:VITE_PEDIDOS_CARRITO_MODE='HTTP'
npm run build
$env:VITE_PEDIDOS_CARRITO_MODE='RABBITMQ'
npm run build
```

El modo se asigna exclusivamente al proceso local de build; no activa backend.
Para teclado: ejecutar `npm run preview:ui` después de las suites; entrar en Carrito
y Continuar a confirmar pedido. Enviar POST con body `error` a `/__preview/scenario`
del puerto loopback impreso; cambiar cuenta ficticia sin abandonar checkout.
Comprobar foco y Tab. Restaurar el escenario con body `normal`; pulsar Enter en
Reintentar consulta del carrito. Estos endpoints pertenecen únicamente al fixture.

Logs, capturas y keyboard.json locales en
`C:/Users/Rodri/Documents/ChatGPT/Pedidos360 Delivery/evidence/issue60-read-recovery-20261009`.
SHA-256 de evidencia y fuentes en `frontend-60-read-recovery-tests.json`.
Los binarios/capturas no se versionan; conservar esa carpeta para revisión local.

Sin backend, contratos, flags operativos, infraestructura, merges o cierre de issues.
Fases visuales 4/6, corte y validación AWS permanecen fuera del alcance.

**READY FOR INDEPENDENT AUDIT**.
